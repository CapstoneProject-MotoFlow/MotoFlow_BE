package fu.hl.g64.motorflow.partner.repository;

import fu.hl.g64.motorflow.partner.entity.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface SupplierRepository extends JpaRepository<Supplier, Long>, JpaSpecificationExecutor<Supplier> {

    boolean existsBySupplierCode(String supplierCode);

    boolean existsBySupplierNameIgnoreCase(String supplierName);

    boolean existsBySupplierNameIgnoreCaseAndIdNot(String supplierName, Long id);

    /** Number of the next generated code (V12); a rolled-back create only leaves a gap. */
    @Query(value = "select nextval('supplier_code_seq')", nativeQuery = true)
    long nextCodeNumber();
}
