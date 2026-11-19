-- R-01: search ignores case and Vietnamese accents, so "nguyen van an" finds "Nguyễn Văn An".
-- Built on translate() instead of the unaccent extension: CREATE EXTENSION needs a superuser and the
-- embedded test PostgreSQL may not ship it. Upper case is mapped in the table itself, because lower() of
-- "Ễ" depends on the database collation. normalize() first joins accents typed as separate marks (NFD).
-- SearchText.fold does the same in Java; SearchFoldTest checks that both agree.
CREATE FUNCTION search_fold(value text) RETURNS text
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
    AS $$ SELECT lower(translate(normalize($1, NFC),
        'àÀáÁạẠảẢãÃâÂầẦấẤậẬẩẨẫẪăĂằẰắẮặẶẳẲẵẴèÈéÉẹẸẻẺẽẼêÊềỀếẾệỆểỂễỄìÌíÍịỊỉỈĩĨòÒóÓọỌỏỎõÕôÔồỒốỐộỘổỔỗỖơƠờỜớỚợỢởỞỡỠùÙúÚụỤủỦũŨưƯừỪứỨựỰửỬữỮỳỲýÝỵỴỷỶỹỸđĐABCDEFGHIJKLMNOPQRSTUVWXYZ',
        'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaeeeeeeeeeeeeeeeeeeeeeeiiiiiiiiiioooooooooooooooooooooooooooooooooouuuuuuuuuuuuuuuuuuuuuuyyyyyyyyyyddabcdefghijklmnopqrstuvwxyz')) $$;
