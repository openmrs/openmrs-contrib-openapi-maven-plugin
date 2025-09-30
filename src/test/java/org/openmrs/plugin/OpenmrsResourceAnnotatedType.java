package org.openmrs.plugin;

import org.openmrs.module.webservices.rest.web.representation.Representation;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceHandler;

import io.swagger.v3.core.converter.AnnotatedType;

public class OpenmrsResourceAnnotatedType<T> extends AnnotatedType {
  private DelegatingResourceHandler<T> handler;

  public OpenmrsResourceAnnotatedType(Class<T> resourceClass, DelegatingResourceHandler<T> handler) {
    super(resourceClass);
    this.handler = handler;
  }

  public DelegatingResourceHandler<T> getHandler() {
    return handler;
  }
}
