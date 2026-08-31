package aijob.config;

import aijob.tailor.v3.TailorEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class TailorEngineProperties {

    public static final String PROPERTY = "AIJOB_TAILOR_ENGINE";
    public static final TailorEngine DEFAULT = TailorEngine.V2;

    private final TailorEngine engine;

    @Autowired
    public TailorEngineProperties(Environment environment) {
        this(TailorEngine.fromProperty(environment == null ? null : environment.getProperty(PROPERTY)));
    }

    public TailorEngineProperties(TailorEngine engine) {
        this.engine = engine == null ? DEFAULT : engine;
    }

    public static TailorEngineProperties defaults() {
        return new TailorEngineProperties(DEFAULT);
    }

    public TailorEngine engine() {
        return engine;
    }
}
