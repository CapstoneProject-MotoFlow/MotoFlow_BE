package fu.hl.g64.motorflow.transfer.dto;

import jakarta.validation.constraints.Size;

/** PM 23/11 case 7: why the Owner finds the receiving store counted the line wrong; required (MSG343). */
public record ReceiverMiscountRequest(
        @Size(max = 255, message = "Lý do tối đa 255 ký tự.")
        String reason) {
}
