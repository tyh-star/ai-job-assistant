package aijob.util;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectDomainRegistryTest {

    @Test
    void unknownTokensAreNotAliases() {
        assertTrue(ObjectDomainRegistry.domainsIn("Prime Deals AlexMorgan nodeId p-16").isEmpty());
        assertTrue(ObjectDomainRegistry.domainsIn("microservices event-driven graphql").isEmpty());
        assertFalse(ObjectDomainRegistry.contains("Designed and implemented a service", ObjectDomainRegistry.BACKEND));
    }

    @Test
    void backendMarkersAreControlled() {
        assertTrue(ObjectDomainRegistry.contains("Java Spring Boot with MVC", ObjectDomainRegistry.BACKEND));
        assertTrue(ObjectDomainRegistry.contains("RESTful APIs", ObjectDomainRegistry.BACKEND));
        assertTrue(ObjectDomainRegistry.contains("controller and repository layer", ObjectDomainRegistry.BACKEND));
        assertTrue(ObjectDomainRegistry.contains(
                "Authentication handled by the server", ObjectDomainRegistry.BACKEND));
        assertFalse(ObjectDomainRegistry.contains("Designed a Vue landing page", ObjectDomainRegistry.BACKEND));
    }

    @Test
    void frontendConflictsWithBackendUnlessBackendIsAlsoPresent() {
        assertTrue(ObjectDomainRegistry.contains("Vue landing page", ObjectDomainRegistry.FRONTEND));
        assertTrue(ObjectDomainRegistry.conflicts(
                java.util.List.of(ObjectDomainRegistry.BACKEND),
                ObjectDomainRegistry.domainsIn("Designed and implemented a Vue landing page")));
        assertFalse(ObjectDomainRegistry.conflicts(
                java.util.List.of(ObjectDomainRegistry.BACKEND),
                ObjectDomainRegistry.domainsIn("Spring Boot REST API")));
    }

    @Test
    void databaseCanSatisfyBackendButDoesNotInventUnknownFamilies() {
        assertTrue(ObjectDomainRegistry.satisfies(
                ObjectDomainRegistry.BACKEND,
                ObjectDomainRegistry.domainsIn("MySQL persistence and SQL queries")));
        assertEquals(Set.of(ObjectDomainRegistry.DATABASE),
                ObjectDomainRegistry.domainsIn("SQL queries"));
        assertFalse(ObjectDomainRegistry.familyIds().contains("MICROSERVICES"));
    }
}
