package fu.hl.g64.motorflow.pricing.service;

import fu.hl.g64.motorflow.common.dto.PageResponse;
import fu.hl.g64.motorflow.pricing.dto.PriceVersionResponse;
import fu.hl.g64.motorflow.pricing.dto.RetailPriceRequest;
import fu.hl.g64.motorflow.pricing.dto.RetailPriceResponse;
import fu.hl.g64.motorflow.pricing.dto.RetailPriceSearch;
import fu.hl.g64.motorflow.security.AuthenticatedUser;

import java.util.List;

/** UC-34 View Price List, UC-35 Update Retail Price and UC-36 View Price History (BR-08, BR-09, BR-69, BR-72). */
public interface RetailPriceService {

    /**
     * UC-34: parts by SKU with the chain-wide price in effect and the one waiting for its date. Owner and a Branch
     * Manager holding retail price permission also get the suggested price (BR-69); only Owner gets what it is
     * built from (BR-72). The {@code suggestionDiffers} filter is for those who see suggested prices (MSG05).
     */
    PageResponse<RetailPriceResponse> list(AuthenticatedUser user, RetailPriceSearch filter, int page, int size);

    /** UC-34: one part of the price list, as {@link #list} shows it. MSG57 if the part does not exist. */
    RetailPriceResponse get(AuthenticatedUser user, long productId);

    /**
     * UC-35: sets the retail price of a part for the whole chain from the chosen day (BR-08, BR-09): right away for
     * today, at the start of a later day. A price for a later day replaces the price already waiting; one for today
     * keeps it. Applying the suggested price needs the price the system suggests now (MSG126). Orders already
     * confirmed keep their price (BR-18). MSG57 unknown part, MSG125 a day before today.
     */
    RetailPriceResponse update(AuthenticatedUser actor, RetailPriceRequest request);

    /** UC-36: every retail price version of a part, newest first, with who saved it, why and from which source. */
    List<PriceVersionResponse> history(long productId);
}
