package org.openmrs.plugin.openapi;

import java.util.Collections;
import java.util.List;

import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceHandler;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingSubclassHandler;

import io.swagger.v3.core.converter.AnnotatedType;

public class OpenmrsResourceAnnotatedType<T> extends AnnotatedType {
  private final DelegatingResourceHandler<T> handler;

  /**
   * The subclass handlers bound to this resource, i.e. the ones
   * {@code BaseDelegatingResource.init()} would attach. Empty for a resource with no class
   * hierarchy, which is the overwhelming majority.
   */
  private final List<DelegatingSubclassHandler<?, ?>> subclassHandlers;

  /**
   * Name to key this handler's schemas by, overriding the one derived from the handler class.
   * Set when generating a subclass handler's schemas as a variant of its parent resource, so
   * {@code DrugOrderSubclassHandler1_12} produces {@code OrderGet_default}, not
   * {@code DrugOrderSubclassHandler1_12Get_default}.
   */
  private final String schemaName;

  public OpenmrsResourceAnnotatedType(DelegatingResourceHandler<T> handler) {
    this(handler, Collections.<DelegatingSubclassHandler<?, ?>> emptyList(), null);
  }

  public OpenmrsResourceAnnotatedType(DelegatingResourceHandler<T> handler,
      List<DelegatingSubclassHandler<?, ?>> subclassHandlers, String schemaName) {
    super(handler.getClass());
    this.handler = handler;
    this.subclassHandlers = subclassHandlers == null
        ? Collections.<DelegatingSubclassHandler<?, ?>> emptyList() : subclassHandlers;
    this.schemaName = schemaName;
  }

  public DelegatingResourceHandler<T> getHandler() {
    return handler;
  }

  public List<DelegatingSubclassHandler<?, ?>> getSubclassHandlers() {
    return subclassHandlers;
  }

  /** The overridden schema name, or null to derive it from the handler class. */
  public String getSchemaName() {
    return schemaName;
  }
}
