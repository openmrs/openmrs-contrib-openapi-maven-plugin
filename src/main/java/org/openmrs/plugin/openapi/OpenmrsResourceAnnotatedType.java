package org.openmrs.plugin.openapi;

import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceHandler;

import io.swagger.v3.core.converter.AnnotatedType;

public class OpenmrsResourceAnnotatedType<T> extends AnnotatedType {
  private DelegatingResourceHandler<T> handler;

  public OpenmrsResourceAnnotatedType(DelegatingResourceHandler<T> handler) {
    super(handler.getClass());
    this.handler = handler;
  }

  public DelegatingResourceHandler<T> getHandler() {
    return handler;
  }
}
