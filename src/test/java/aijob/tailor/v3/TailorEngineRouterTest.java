package aijob.tailor.v3;

import aijob.config.TailorEngineProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TailorEngineRouterTest {

    @Test
    void defaultsToV2WhenUnsetOrBlankOrUnknown() {
        assertEquals(TailorEngine.V2, TailorEngine.fromProperty(null));
        assertEquals(TailorEngine.V2, TailorEngine.fromProperty(""));
        assertEquals(TailorEngine.V2, TailorEngine.fromProperty("nope"));
        assertEquals(TailorEngine.V2, TailorEngineProperties.defaults().engine());
        MockEnvironment env = new MockEnvironment();
        assertEquals(TailorEngine.V2, new TailorEngineProperties(env).engine());
        env.setProperty(TailorEngineProperties.PROPERTY, "  ");
        assertEquals(TailorEngine.V2, new TailorEngineProperties(env).engine());
    }

    @Test
    void selectsV3OnlyForExactV3Token() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty(TailorEngineProperties.PROPERTY, "v3");
        TailorEngineRouter router = new TailorEngineRouter(new TailorEngineProperties(env));
        assertTrue(router.useV3());
        assertFalse(router.useV2());
        env.setProperty(TailorEngineProperties.PROPERTY, "V2");
        assertTrue(new TailorEngineRouter(new TailorEngineProperties(env)).useV2());
    }
}
