package com.powercms.cms.workflow;

import java.util.Optional;

/**
 * Resolves a workflow username to an email address. Identity provides the default implementation.
 */
@FunctionalInterface
public interface RecipientEmailResolver {
    Optional<String> emailFor(String username);
}
