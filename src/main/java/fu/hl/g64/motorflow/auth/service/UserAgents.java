package fu.hl.g64.motorflow.auth.service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a stored User-Agent into a short label such as "Chrome 141 · Windows" for Admin screens.
 * shortcut: covers the browsers and systems the shops use; switch to a UA library if labels start coming out wrong.
 */
public final class UserAgents {

    private record Rule(String name, Pattern pattern) {
    }

    // Order matters: Edge, Opera and Samsung Internet also send "Chrome/", and Chrome also sends "Safari/".
    private static final List<Rule> BROWSERS = List.of(
            new Rule("Edge", Pattern.compile("Edg(?:e|A|iOS)?/(\\d+)")),
            new Rule("Opera", Pattern.compile("OPR/(\\d+)")),
            new Rule("Samsung Internet", Pattern.compile("SamsungBrowser/(\\d+)")),
            new Rule("Chrome", Pattern.compile("CriOS/(\\d+)")),
            new Rule("Firefox", Pattern.compile("(?:Firefox|FxiOS)/(\\d+)")),
            new Rule("Chrome", Pattern.compile("Chrome/(\\d+)")),
            new Rule("Safari", Pattern.compile("Version/(\\d+)[^ ]* (?:Mobile/\\S+ )?Safari/")),
            new Rule("Postman", Pattern.compile("PostmanRuntime/(\\d+)")),
            new Rule("PowerShell", Pattern.compile("PowerShell/(\\d+)")));

    private static final Pattern ANDROID = Pattern.compile("Android (\\d+)");
    private static final Pattern IOS = Pattern.compile("OS (\\d+)_\\d+.* like Mac OS X");

    private UserAgents() {
    }

    /** Null when the User-Agent is missing. Unknown agents show their first product token, e.g. "curl". */
    public static String describe(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        String browser = browser(userAgent);
        String os = os(userAgent);
        return os == null ? browser : browser + " · " + os;
    }

    private static String browser(String ua) {
        for (Rule rule : BROWSERS) {
            Matcher m = rule.pattern().matcher(ua);
            if (m.find()) {
                return rule.name() + " " + m.group(1);
            }
        }
        return ua.strip().split("[/ ]", 2)[0];
    }

    private static String os(String ua) {
        Matcher android = ANDROID.matcher(ua);
        if (android.find()) {
            return "Android " + android.group(1);
        }
        if (ua.contains("iPhone") || ua.contains("iPad")) {
            Matcher ios = IOS.matcher(ua);
            String device = ua.contains("iPad") ? "iPad" : "iPhone";
            return ios.find() ? device + " (iOS " + ios.group(1) + ")" : device;
        }
        if (ua.contains("Windows")) {
            return "Windows";
        }
        if (ua.contains("Mac OS X")) {
            return "macOS";
        }
        if (ua.contains("Linux")) {
            return "Linux";
        }
        return null;
    }
}
