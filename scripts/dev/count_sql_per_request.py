"""Count the SQL statements of each API request in a log of the dev profile.

Run the app with the dev profile, save its console to a file, call the APIs (Postman, the FE), then:
    python3 scripts/dev/count_sql_per_request.py app.log [top]
Prints one line per endpoint (ids replaced by {id}): most and fewest statements per call, number of calls, and the
statement repeated most often in the heaviest call (a hint of N+1). The session check that the JWT filter runs before
the request reaches Spring MVC is not inside a request, so add 1 for each signed-in call.
"""
import collections
import re
import sys

LINE = re.compile(r'^\S+\s+\w+\s+\d+ --- \[[^\]]*\] \[\s*([^\]]+)\] (\S+)\s*: (.*)$')
START = re.compile(r'(GET|POST|PUT|DELETE|PATCH) "([^"]+)"')


def main(path, top):
    open_requests, done, outside = {}, [], 0
    with open(path, encoding='utf-8', errors='replace') as log:
        for raw in log:
            match = LINE.match(raw.rstrip('\n'))
            if not match:
                continue
            thread, logger, message = match.groups()
            if logger.endswith('DispatcherServlet'):
                start = START.match(message)
                if start:
                    open_requests[thread] = {'method': start.group(1), 'url': start.group(2),
                                             'sql': collections.Counter()}
                elif message.startswith('Completed') and thread in open_requests:
                    done.append(open_requests.pop(thread))
                continue
            is_sql = logger == 'org.hibernate.SQL' or ('JdbcTemplate' in logger and message.startswith('Executing'))
            if not is_sql:
                continue
            if thread in open_requests:
                open_requests[thread]['sql'][re.sub(r'\s+', ' ', message)[:110]] += 1
            else:
                outside += 1

    by_endpoint = collections.defaultdict(list)
    for request in done:
        endpoint = re.sub(r'/\d+(?=/|$)', '/{id}', request['url'].split('?')[0])
        by_endpoint[(request['method'], endpoint)].append(request)
    total = sum(sum(r['sql'].values()) for r in done)
    print(f'{len(done)} requests, {total} statements inside them '
          f'({total / max(len(done), 1):.1f} each), {outside} outside a request')
    print(' most fewest calls  endpoint  | most repeated statement in the heaviest call')
    ranked = sorted(by_endpoint.items(), key=lambda kv: -max(sum(r['sql'].values()) for r in kv[1]))
    for (method, endpoint), calls in ranked[:top]:
        counts = [sum(r['sql'].values()) for r in calls]
        heaviest = max(calls, key=lambda r: sum(r['sql'].values()))['sql']
        repeated = heaviest.most_common(1)[0] if heaviest else (0, 0)
        hint = f'{repeated[1]}x {repeated[0]}' if heaviest else ''
        print(f'{max(counts):5d} {min(counts):6d} {len(calls):5d}  {method} {endpoint}  | {hint}')


if __name__ == '__main__':
    main(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 40)
