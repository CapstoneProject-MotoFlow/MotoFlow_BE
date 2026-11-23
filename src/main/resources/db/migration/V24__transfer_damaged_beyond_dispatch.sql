-- PM 23/11 (test DC-0066): a transfer receipt may record more damaged units than were dispatched. Up to the dispatched
-- quantity they are written off from in-transit stock as before; the rest is kept on the receipt only and moves no
-- stock in either store.
ALTER TABLE goods_receipt_note_line
    ADD COLUMN damaged_beyond_qty INT NOT NULL DEFAULT 0 CHECK (damaged_beyond_qty >= 0);
