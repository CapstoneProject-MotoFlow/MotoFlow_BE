package fu.hl.g64.motorflow.catalog.controller;

/** Who may use the catalog (BR-05, Permission Matrix). Admin has no access to business data. */
final class CatalogAccess {

    static final String READ = "hasAnyRole('OWNER', 'BRANCH_MANAGER', 'STAFF')";
    static final String EDIT = "hasRole('OWNER') or hasAuthority('PERM_CATALOG_EDIT')";

    private CatalogAccess() {
    }
}
