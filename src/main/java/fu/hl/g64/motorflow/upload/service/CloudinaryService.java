package fu.hl.g64.motorflow.upload.service;

import fu.hl.g64.motorflow.upload.dto.UploadPurpose;
import fu.hl.g64.motorflow.upload.dto.UploadSignatureResponse;

/**
 * Signs browser uploads to Cloudinary and builds download links for private files, so the API secret
 * never leaves the server and files never pass through it.
 */
public interface CloudinaryService {
    UploadSignatureResponse uploadSignature(UploadPurpose purpose);

    /**
     * A link to the original of a private file that stops working after motoflow.cloudinary.download-url-ttl.
     * Call it only after checking that the user may see the record the file belongs to.
     */
    String privateDownloadUrl(String publicId, String format);

    /** The start of every image this account delivers publicly, or null when uploads are off. */
    String publicImagePrefix();
}
