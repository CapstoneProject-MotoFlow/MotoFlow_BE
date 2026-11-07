package fu.hl.g64.motorflow.operation.repository;

import fu.hl.g64.motorflow.operation.entity.Attachment;
import fu.hl.g64.motorflow.operation.entity.enums.OwnerType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    /** The files of the given records, oldest first. */
    List<Attachment> findByOwnerTypeAndOwnerIdInOrderByUploadedAtAscIdAsc(OwnerType ownerType,
                                                                          Collection<Long> ownerIds);

    /** Storage keys among the given ones that are already attached to a record (storage_key is unique). */
    List<Attachment> findByStorageKeyIn(Collection<String> storageKeys);
}
