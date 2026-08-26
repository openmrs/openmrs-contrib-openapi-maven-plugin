package org.openmrs.plugin.openapi;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.openmrs.api.ServiceNotFoundException;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.ServiceContext;
import org.openmrs.api.context.UserContext;
import org.openmrs.module.webservices.rest.web.RestUtil;
import org.openmrs.module.webservices.rest.web.api.RestService;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stands in for the pieces of the OpenMRS runtime that resource handlers reach for while
 * describing themselves, so that schema generation needs no Spring context, no database and no
 * authenticated session.
 * <p>
 * Almost all handlers are self-contained: they build a {@code DelegatingResourceDescription} out
 * of string literals. The few that are not fall into three groups, all covered here:
 * <ul>
 * <li>{@code RestConstants}' static initializer calls
 * {@code RestUtil.setUriPrefix() -> Context.getAdministrationService()}. This throws with no
 * session, and every later touch then fails with
 * {@code NoClassDefFoundError: Could not initialize class RestConstants} — which makes one bug
 * look like dozens. {@link #install} defuses it via {@link RestUtil#disableContext()} before the
 * class is ever loaded.</li>
 * <li>Subclass handlers look each other up through {@code Context.getService(RestService.class)};
 * {@link #registerRestService} answers those from the handlers we already discovered, so the
 * result is identical to what the real {@code RestServiceImpl} would return.</li>
 * <li>A handful call other services or {@code Context.hasPrivilege(...)}; those get no-op proxies
 * and an all-privileges user context.</li>
 * </ul>
 * Every stubbed service call is recorded and can be reported via {@link #getStubbedServices()} —
 * a stub that silently returns a default could change a schema without failing, so the calls are
 * made visible rather than hidden.
 */
public final class StubRuntime {

    private static final Logger log = LoggerFactory.getLogger(StubRuntime.class);

    /** Services requested through Context for which a no-op proxy was handed back. */
    private static final Set<String> stubbedServices = Collections.synchronizedSet(new TreeSet<String>());

    private StubRuntime() {
    }

    /**
     * Installs the stubs. Must run before any resource class — and therefore
     * {@code RestConstants} — is loaded.
     */
    public static void install() {
        // Order matters: this has to happen before RestConstants' static initializer runs.
        RestUtil.disableContext();
        Context.setUserContext(new AllPrivilegesUserContext());
        System.out.println("Installed stub runtime (RestUtil context disabled, all-privileges user context)");
    }

    /**
     * Registers a {@link RestService} that answers resource lookups from the already-discovered
     * handlers. This is a faithful substitute rather than a fake: {@code RestServiceImpl} resolves
     * these from the same annotation data.
     */
    public static void registerRestService(List<DelegatingResourceHandler<?>> handlers) {
        final Map<Class<?>, Object> bySupportedClass = new LinkedHashMap<Class<?>, Object>();
        final Map<String, Object> byName = new LinkedHashMap<String, Object>();

        for (DelegatingResourceHandler<?> handler : handlers) {
            org.openmrs.module.webservices.rest.web.annotation.Resource annotation = handler.getClass()
                    .getAnnotation(org.openmrs.module.webservices.rest.web.annotation.Resource.class);
            if (annotation == null) {
                continue;
            }
            if (!bySupportedClass.containsKey(annotation.supportedClass())) {
                bySupportedClass.put(annotation.supportedClass(), handler);
            }
            if (!byName.containsKey(annotation.name())) {
                byName.put(annotation.name(), handler);
            }
        }

        Object restService = Proxy.newProxyInstance(
                RestService.class.getClassLoader(),
                new Class<?>[] { RestService.class },
                new InvocationHandler() {

                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("getResourceBySupportedClass".equals(name) && args != null && args.length == 1) {
                            return bySupportedClass.get(args[0]);
                        }
                        if ("getResourceByName".equals(name) && args != null && args.length == 1) {
                            return byName.get(args[0]);
                        }
                        if ("getResourceHandlers".equals(name)) {
                            return new ArrayList<Object>(bySupportedClass.values());
                        }
                        stubbedServices.add(RestService.class.getName() + "." + name);
                        return defaultValueFor(method.getReturnType());
                    }
                });

        ServiceContext.getInstance().setService(RestService.class, restService);
        // This is the stub's internal lookup table, not a count of what gets documented — it
        // spans every resource on the classpath so subclass handlers can resolve their parents.
        System.out.println("Stub RestService can resolve " + bySupportedClass.size()
                + " resources by supported class");
    }

    /**
     * Registers a no-op proxy for a service a resource asked for but that is not available
     * without a running platform. Returns false if the class cannot be proxied.
     */
    public static boolean registerNoOpService(Class<?> serviceInterface) {
        if (!serviceInterface.isInterface()) {
            return false;
        }
        final String serviceName = serviceInterface.getName();
        Object proxy = Proxy.newProxyInstance(
                serviceInterface.getClassLoader(),
                new Class<?>[] { serviceInterface },
                new InvocationHandler() {

                    @Override
                    public Object invoke(Object proxyInstance, Method method, Object[] args) {
                        stubbedServices.add(serviceName + "." + method.getName());
                        return defaultValueFor(method.getReturnType());
                    }
                });
        try {
            ServiceContext.getInstance().setService(serviceInterface, proxy);
            return true;
        } catch (Throwable t) {
            log.debug("Could not register no-op service for {}: {}", serviceName, t.toString());
            return false;
        }
    }

    /**
     * Returns a usable default for a proxied method's return type. Returning null blindly would
     * throw NPE on unboxing for primitive-returning methods.
     */
    private static Object defaultValueFor(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            if (List.class.equals(returnType) || Collection.class.equals(returnType)) {
                return new ArrayList<Object>();
            }
            if (Set.class.equals(returnType)) {
                return new LinkedHashSet<Object>();
            }
            if (Map.class.equals(returnType)) {
                return new LinkedHashMap<Object, Object>();
            }
            return null;
        }
        if (boolean.class.equals(returnType)) {
            return Boolean.FALSE;
        }
        if (void.class.equals(returnType)) {
            return null;
        }
        if (char.class.equals(returnType)) {
            return Character.valueOf('\0');
        }
        if (byte.class.equals(returnType)) {
            return Byte.valueOf((byte) 0);
        }
        if (short.class.equals(returnType)) {
            return Short.valueOf((short) 0);
        }
        if (int.class.equals(returnType)) {
            return Integer.valueOf(0);
        }
        if (long.class.equals(returnType)) {
            return Long.valueOf(0L);
        }
        if (float.class.equals(returnType)) {
            return Float.valueOf(0f);
        }
        return Double.valueOf(0d);
    }

    /**
     * Walks a throwable's cause chain looking for a {@code ServiceNotFoundException}, and returns
     * the service interface it names.
     *
     * @return the missing service interface, or null if the failure was something else
     */
    public static Class<?> findMissingServiceClass(Throwable t) {
        Throwable cause = t;
        while (cause != null) {
            if (cause instanceof ServiceNotFoundException) {
                Class<?> serviceClass = ((ServiceNotFoundException) cause).getServiceClass();
                if (serviceClass != null && serviceClass.isInterface()) {
                    return serviceClass;
                }
                return null;
            }
            cause = cause.getCause();
        }
        return null;
    }

    /** Names of every service method answered by a stub during this run. */
    public static Set<String> getStubbedServices() {
        synchronized (stubbedServices) {
            return new TreeSet<String>(stubbedServices);
        }
    }

    /**
     * A user context that grants every privilege.
     * <p>
     * This is a deliberate documentation choice, not a workaround. The only thing the generator
     * asks it is {@code Context.hasPrivilege(RestConstants.PRIV_SET_AUDIT_DATA)}, which decides
     * whether audit fields ({@code creator}, {@code dateCreated}, {@code changedBy}, ...) appear
     * in a create/update schema. An API specification should describe the superset of what a
     * sufficiently privileged caller may send, so this answers true.
     */
    private static final class AllPrivilegesUserContext extends UserContext {

        AllPrivilegesUserContext() {
            super(null);
        }

        @Override
        public boolean hasPrivilege(String privilege) {
            return true;
        }
    }
}
