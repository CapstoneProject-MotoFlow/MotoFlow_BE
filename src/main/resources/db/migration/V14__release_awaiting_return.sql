-- Test report 20/11: until goods are handed back, the store may lower the quantity it agreed to return (it keeps them
-- to sell, for instance, while the supplier still replaces them). The goods leave awaiting return to supplier for the
-- status they were held from: a RELEASED_FROM_AWAITING_RETURN event and RETURN_RELEASE movements.
-- V1 declared both CHECKs inline; PostgreSQL named them <table>_<column>_check.
ALTER TABLE discrepancy_resolution_event DROP CONSTRAINT discrepancy_resolution_event_resolution_type_check;
ALTER TABLE discrepancy_resolution_event ADD CONSTRAINT discrepancy_resolution_event_resolution_type_check
    CHECK (resolution_type IN ('MOVED_TO_AWAITING_RETURN', 'RELEASED_FROM_AWAITING_RETURN', 'RETURNED_TO_SUPPLIER',
                               'REPLACEMENT_RECEIVED', 'LATE_DELIVERY_RECEIVED', 'RECEIVED_BACK_AT_SOURCE',
                               'APPROVED_LOSS', 'CLOSED_BY_AGREEMENT'));

ALTER TABLE stock_movement DROP CONSTRAINT stock_movement_movement_type_check;
ALTER TABLE stock_movement ADD CONSTRAINT stock_movement_movement_type_check
    CHECK (movement_type IN ('SALE_ISSUE', 'RECEIPT_CONFIRMED', 'EXCESS_ACCEPTED', 'EXCESS_REJECTED', 'RETURN_HOLD',
                             'RETURN_RELEASE', 'SUPPLIER_RETURN', 'TRANSFER_DISPATCH', 'TRANSFER_EXTRA_DISPATCH',
                             'TRANSFER_RECEIPT', 'ADJUSTMENT', 'COUNT_ADJUSTMENT', 'OPENING_BALANCE'));
