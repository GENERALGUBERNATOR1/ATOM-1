package com.betamedia.atom.core.api.tp.entities.namingstrategies.customer;

/**
 * @author Maksym Tsybulskyy
 *         Date: 6/8/17.
 */

/**
 * Naming strategy for created through the CRM Mobile API (REST) new users with default parameters
 */
public class CRMMobileAPINamingStrategy extends AbstractUserNamingStrategy {

    public static final String MOBILE_CRM_REGISTER_EMAIL_TEMPLATE = "crm_{dynamicPart}@24options.atom";

    @Override
    protected String getDefaultFirstName() {
        return DEFAULT_USER_FIRST_NAME;
    }

    @Override
    protected String getDefaultLastName() {
        return DEFAULT_USER_LAST_NAME;
    }

    @Override
    protected String getEmailTemplate() {
        return MOBILE_CRM_REGISTER_EMAIL_TEMPLATE;
    }

    @Override
    protected String getEmailDynamicPartRegex() {
        return DEFAULT_DYNAMIC_EMAIL_PART_REGEX;
    }

    public static UserNamingStrategy get() {
        return new CRMMobileAPINamingStrategy();
    }
}
