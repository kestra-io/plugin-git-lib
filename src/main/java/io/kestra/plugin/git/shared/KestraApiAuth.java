package io.kestra.plugin.git.shared;

import io.kestra.core.models.property.Property;

/**
 * The Kestra API connection settings a task exposes under its {@code auth} property.
 *
 * <p>Implemented by the {@code Auth} class of each task hierarchy so that the URL resolution is written once.
 */
public interface KestraApiAuth {
    Property<Boolean> getAuto();
}
