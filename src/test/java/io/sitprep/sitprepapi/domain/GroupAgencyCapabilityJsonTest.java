package io.sitprep.sitprepapi.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.constant.AgencyCapability;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GroupAgencyCapabilityJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void agencyAuthorityFieldsAreOutputOnly() throws Exception {
        Group request = mapper.readValue("""
                {
                  "groupId": "crafted-group",
                  "agencyAuthorized": true,
                  "agencyCapabilities": ["SEND_AREA_ALERTS"]
                }
                """, Group.class);

        assertThat(request.isAgencyAuthorized()).isFalse();
        assertThat(request.getAgencyCapabilities()).isEmpty();

        request.setAgencyAuthorized(true);
        request.getAgencyCapabilities().add(AgencyCapability.SEND_AREA_ALERTS);
        String response = mapper.writeValueAsString(request);
        assertThat(response).contains("\"agencyAuthorized\":true");
        assertThat(response).contains("SEND_AREA_ALERTS");
    }
}
