package fu.hl.g64.motorflow.partner.repository;

import fu.hl.g64.motorflow.partner.entity.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SupplierRepository extends JpaRepository<Supplier, Long>, JpaSpecificationExecutor<Supplier> {

    boolean existsBySupplierCode(String supplierCode);
}
