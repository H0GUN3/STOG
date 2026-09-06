package com.stog.backend.compat;

/** Approved public-data source boundary for the catalog refresh command. */
@FunctionalInterface
public interface CatalogRefreshProvider {
    PublicCatalogSnapshot fetch(String runId);
}
