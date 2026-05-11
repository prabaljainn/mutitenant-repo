package com.orochiverse.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;


/**
 * Phase 1.2 integration test — proves the Spring Data Mongo wiring works
 * against a real MongoDB 8 replica-set node.
 *
 * Why not Testcontainers? As of May 2026, the docker-java client bundled
 * with Testcontainers 1.21.x doesn't yet recognise Docker Desktop 29.x's
 * Engine API (1.53). Until that's fixed upstream, integration tests run
 * against the local dev stack started by {@code ./scripts/dev-up.sh}.
 *
 * The {@link com.orochiverse.platform.testsupport.MongoTestSupport#mongoIsReachable
 * mongoIsReachable} guard means CI and dev machines that don't have the
 * stack up will SKIP the test (not fail it), keeping {@code mvn verify}
 * green while still exercising the contract whenever Mongo is available.
 */
@SpringBootTest
@ActiveProfiles("integration")
@EnabledIf("com.orochiverse.platform.testsupport.MongoTestSupport#mongoIsReachable")
class MongoConnectivityIT {

    // Distinct DB name keeps this smoke test's documents out of iam_db.
    private static final String DB_NAME = "iam_db_it_smoke";
    private static final String CONNECTION_URI =
            "mongodb://localhost:27017/" + DB_NAME + "?replicaSet=rs0&directConnection=true";

    @DynamicPropertySource
    static void mongoProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> CONNECTION_URI);
    }

    @Autowired
    MongoTemplate mongo;

    @BeforeAll
    static void announce() {
        System.out.println("[MongoConnectivityIT] Connecting to " + CONNECTION_URI);
    }

    @Test
    void mongo_template_is_wired_and_reachable() {
        assertThat(mongo).isNotNull();
        assertThat(mongo.getDb().getName()).isEqualTo(DB_NAME);
        assertThat(mongo.executeCommand("{ ping: 1 }").get("ok")).isEqualTo(1.0);
    }

    @Test
    void can_write_and_read_a_document() {
        var collection = "phase_1_2_smoke";
        mongo.dropCollection(collection);
        mongo.insert(new Document("hello", "orochiverse"), collection);
        var found = mongo.getCollection(collection).find().first();
        assertThat(found).isNotNull();
        assertThat(found.getString("hello")).isEqualTo("orochiverse");
        mongo.dropCollection(collection);
    }

    @Test
    void replica_set_is_initialised_and_writable() {
        var hello = mongo.executeCommand("{ hello: 1 }");
        assertThat(hello.getBoolean("isWritablePrimary")).isTrue();
        assertThat(hello.getString("setName")).isEqualTo("rs0");
    }
}
