-- GVHD 24/11 (plan B): the direction agreed with the supplier for each supplier discrepancy case is decided first, and
-- only then acted on. A Branch Manager decides alone only "return and ask for a replacement" with the full
-- replacement; anything else waits for the Owner (proposed_* hold the proposal until then).
ALTER TABLE discrepancy_case
    ADD COLUMN decision_type VARCHAR(25)
        CHECK (decision_type IN ('RETURN_AND_REPLACE', 'RETURN_NO_REPLACEMENT', 'ACCEPT_NO_REPLACEMENT')),
    ADD COLUMN decision_status VARCHAR(15) NOT NULL DEFAULT 'UNDECIDED'
        CHECK (decision_status IN ('UNDECIDED', 'AWAITING_OWNER', 'DECIDED')),
    -- Review 25/11: the direction decided before a proposal, in force again if the Owner rejects it.
    ADD COLUMN prior_decision_type VARCHAR(25)
        CHECK (prior_decision_type IN ('RETURN_AND_REPLACE', 'RETURN_NO_REPLACEMENT', 'ACCEPT_NO_REPLACEMENT')),
    ADD COLUMN proposed_wrong_return_qty INTEGER CHECK (proposed_wrong_return_qty >= 0),
    ADD COLUMN proposed_damaged_return_qty INTEGER CHECK (proposed_damaged_return_qty >= 0),
    ADD COLUMN proposed_replacement_qty INTEGER CHECK (proposed_replacement_qty >= 0),
    ADD COLUMN decided_by BIGINT REFERENCES user_account(id),
    ADD COLUMN decided_at TIMESTAMP,
    ADD COLUMN approved_by BIGINT REFERENCES user_account(id),
    ADD COLUMN approved_at TIMESTAMP,
    ADD CONSTRAINT ck_dc_decision CHECK ((decision_status = 'UNDECIDED') = (decision_type IS NULL)),
    ADD CONSTRAINT ck_dc_proposal CHECK ((decision_status = 'AWAITING_OWNER') = (proposed_replacement_qty IS NOT NULL
        AND proposed_wrong_return_qty IS NOT NULL AND proposed_damaged_return_qty IS NOT NULL));

-- Backfill (supplier cases only; transfer cases stay UNDECIDED): a case closed, with a return agreed, a replacement
-- owed or anything done on it was decided. The direction is read from what was done: a waiver with a return is
-- (1), goods taken into stock with nothing of that kind going back is (3), anything else (2).
UPDATE discrepancy_case c
SET decision_status = 'DECIDED',
    decision_type = CASE
        WHEN c.wrong_return_required_qty + c.damaged_return_required_qty > 0
             AND EXISTS (SELECT 1 FROM discrepancy_resolution_event e
                         WHERE e.discrepancy_case_id = c.id AND e.resolution_type = 'CLOSED_BY_AGREEMENT')
            THEN 'RETURN_NO_REPLACEMENT'
        WHEN c.wrong_return_required_qty + c.damaged_return_required_qty = 0
             AND EXISTS (SELECT 1 FROM discrepancy_resolution_event e
                         WHERE e.discrepancy_case_id = c.id
                           AND e.resolution_type IN ('ACCEPTED_AS_VARIANT', 'RECEIVED_AS_OTHER_PRODUCT'))
            THEN 'ACCEPT_NO_REPLACEMENT'
        ELSE 'RETURN_AND_REPLACE' END
WHERE c.source_type = 'SUPPLIER'
  AND (c.status = 'CLOSED' OR c.return_required_qty > 0 OR c.replacement_required_qty > 0
       OR EXISTS (SELECT 1 FROM discrepancy_resolution_event e WHERE e.discrepancy_case_id = c.id));

CREATE INDEX idx_discrepancy_case_decision ON discrepancy_case (decision_status) WHERE decision_status = 'AWAITING_OWNER';
