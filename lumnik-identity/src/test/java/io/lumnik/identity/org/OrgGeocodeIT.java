package io.lumnik.identity.org;

import io.lumnik.identity.geo.AddressInput;
import io.lumnik.identity.geo.Coords;
import io.lumnik.identity.geo.Geocoder;
import io.lumnik.identity.tenant.Tenant;
import io.lumnik.identity.tenant.TenantRepository;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class OrgGeocodeIT {

    @InjectMock Geocoder geocoder;

    @Inject TenantRepository tenants;
    @Inject OrgRepository orgs;

    private Long tenantId;

    @BeforeEach
    @Transactional
    void setup() {
        Mockito.when(geocoder.resolve(Mockito.any(AddressInput.class)))
            .thenReturn(Optional.of(new Coords(
                new BigDecimal("48.8566"), new BigDecimal("2.3522"))));
        Tenant t = new Tenant("GEO-TEST", "Geocode Test Tenant");
        tenants.persist(t);
        tenantId = t.getId();
    }

    @AfterEach
    @Transactional
    void cleanup() {
        orgs.delete("tenantId = ?1", tenantId);
        tenants.delete("code = 'GEO-TEST'");
    }

    @Test
    void create_org_with_address_triggers_geocoder() {
        given()
            .contentType("application/json")
            .body("""
                  { "code":"AG-GEOCODE-TEST","name":"Agence Paris",
                    "addressLine1":"1 rue de Rivoli","postalCode":"75001",
                    "city":"Paris","country":"FR","summary":false }
                  """)
        .when().post("/api/tenants/" + tenantId + "/orgs")
        .then().statusCode(201)
            .body("city", equalTo("Paris"))
            .body("latitude", notNullValue())
            .body("longitude", notNullValue())
            .body("geocodedAt", notNullValue());
    }
}
