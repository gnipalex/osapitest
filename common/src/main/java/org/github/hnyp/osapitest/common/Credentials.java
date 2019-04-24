package org.github.hnyp.osapitest.common;

import java.io.IOException;
import java.util.Properties;

public class Credentials {

    private static final Properties PROPS = new Properties();

    static {
        try {
            PROPS.load(Credentials.class.getClassLoader().getResourceAsStream("credentials.properties"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static final String KEYSTONE_AUTH_URL = PROPS.getProperty("keystone.url");
    public static final String USERNAME = PROPS.getProperty("username");
    public static final String PASS = PROPS.getProperty("password");
    public static final String TENANT = PROPS.getProperty("tenant");

}
