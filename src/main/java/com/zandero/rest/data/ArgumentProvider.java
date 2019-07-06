package com.zandero.rest.data;

import com.zandero.rest.context.ContextProvider;
import com.zandero.rest.context.ContextProviderFactory;
import com.zandero.rest.exception.ClassFactoryException;
import com.zandero.rest.exception.ContextException;
import com.zandero.rest.injection.InjectionProvider;
import com.zandero.rest.reader.ReaderFactory;
import com.zandero.rest.reader.ValueReader;
import com.zandero.utils.Assert;
import com.zandero.utils.StringUtils;
import com.zandero.utils.extra.UrlUtils;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.Cookie;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URLDecoder;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts arguments to be provided for given method from definition and current context (request)
 */
public class ArgumentProvider {

    private final static Logger log = LoggerFactory.getLogger(ArgumentProvider.class);

    @SuppressWarnings("unchecked")
    public static Object[] getArguments(Method method,
                                        RouteDefinition definition,
                                        RoutingContext context,
                                        ReaderFactory readers,
                                        ContextProviderFactory providerFactory,
                                        InjectionProvider injectionProvider) throws Throwable {

        Assert.notNull(method, "Missing method to provide arguments for!");
        Assert.notNull(definition, "Missing route definition!");
        Assert.notNull(context, "Missing vert.x routing context!");

        Class<?>[] methodArguments = method.getParameterTypes();

        if (methodArguments.length == 0) {
            return null;    // no arguments needed ...
        }

        // get parameters and extract from request their values
        List<MethodParameter> params = definition.getParameters(); // returned sorted by index

        Object[] args = new Object[methodArguments.length];

        for (MethodParameter parameter : params) {

            if (!parameter.isUsedAsArgument()) {
                continue;
            }

            // set if we have a place to set it ... otherwise ignore
            if (parameter.getIndex() < args.length) {

                // get value
                String value = getValue(definition, parameter, context, parameter.getDefaultValue());

                Class<?> dataType = parameter.getDataType();
                if (dataType == null) {
                    dataType = methodArguments[parameter.getIndex()];
                }

                try {
                    switch (parameter.getType()) {

                        case bean:

                            try {
                                Object bean = ClassFactory.newInstanceOf(dataType, injectionProvider, context);

                                RouteDefinition beanDefinition = new RouteDefinition(dataType);
                                Map<RouteDefinition, Method> output = new LinkedHashMap<>();

                                for (Method beanMethod : dataType.getMethods()) {

                                    try {
                                        RouteDefinition beanMethodDefinition = new RouteDefinition(beanDefinition, method);
                                        output.put(beanMethodDefinition, method);
                                    } catch (IllegalArgumentException e) {

                                       // throw new IllegalArgumentException(getClassMethod(clazz, method) + " - " + e.getMessage());
                                    }
                                }


                                // once we have a bean we can populate his properties
                                //Map<RouteDefinition, Method> beanDefinition = AnnotationProcessor.getDefinitions(dataType);

                                args[parameter.getIndex()] = bean;

                            } catch (ClassFactoryException | ContextException e) {
                                throw new IllegalArgumentException(e.getMessage());
                            }
                            break;

                        case context:

                            // check if providers need to be called to assure context
                            ContextProvider provider = providerFactory.get(dataType, parameter.getContextProvider(), injectionProvider, context, null);
                            if (provider != null) {
                                Object result = provider.provide(context.request());
                                if (result != null) {
                                    context.data().put(ContextProviderFactory.getContextKey(dataType), result);
                                }
                            }

                            args[parameter.getIndex()] = ContextProviderFactory.provideContext(method.getParameterTypes()[parameter.getIndex()],
                                    parameter.getDefaultValue(),
                                    context);
                            break;

                        default:

                            ValueReader valueReader = getValueReader(injectionProvider, parameter, definition, context, readers);
                            args[parameter.getIndex()] = valueReader.read(value, dataType);
                            break;
                    }
                } catch (Throwable e) {

                    if (e instanceof ContextException) {
                        throw new IllegalArgumentException(e.getMessage());
                    }

                    if (e instanceof IllegalArgumentException) {

                        MethodParameter paramDefinition = definition.findParameter(parameter.getIndex());
                        String providedType = value != null ? value.getClass().getSimpleName() : "null";
                        String expectedType = method.getParameterTypes()[parameter.getIndex()].getTypeName();

                        String error;
                        if (paramDefinition != null) {
                            error =
                                    "Invalid parameter type for: " + paramDefinition + " for: " + definition.getPath() + ", expected: " + expectedType;
                        } else {
                            error =
                                    "Invalid parameter type for " + (parameter.getIndex() + 1) + " argument for: " + method + " expected: " +
                                            expectedType;
                        }

                        if (!StringUtils.equals(expectedType, providedType, false)) {
                            error = error + ", but got: " + providedType;
                        }

                        error = error + " -> " + e;

                        throw new IllegalArgumentException(error, e);
                    }

                    throw e;
                }
            }
        }

        // parameter check ...
        for (int index = 0; index < args.length; index++) {
            Parameter param = method.getParameters()[index];
            if (args[index] == null && param.getType().isPrimitive()) {

                MethodParameter paramDefinition = definition.findParameter(index);
                if (paramDefinition != null) {
                    throw new IllegalArgumentException("Missing " + paramDefinition + " for: " + definition.getPath());
                }

                throw new IllegalArgumentException("Missing " + (index + 1) + " argument for: " + method +
                        " expected: " + param.getType() + ", but: null was provided!");
            }
        }

        return args;
    }

    private static String getValue(RouteDefinition definition, MethodParameter param, RoutingContext context, String defaultValue) {

        String value = getValue(definition, param, context);

        if (value == null) {
            return defaultValue;
        }

        return value;
    }

    private static String getValue(RouteDefinition definition, MethodParameter param, RoutingContext context) {

        switch (param.getType()) {
            case path:

                String path;
                if (definition.pathIsRegEx()) { // RegEx is special, params values are given by index
                    path = getParam(context.mountPoint(), context.request(), param.getPathIndex());
                } else {
                    path = context.request().getParam(param.getName());
                }

                // if @MatrixParams are present ... those need to be removed
                path = removeMatrixFromPath(path, definition);
                return path;

            case query:
                Map<String, String> query = UrlUtils.getQuery(context.request().query());
                String value = query.get(param.getName());

                // user specified @Raw annotation ... provide as it is
                if (param.isRaw()) {
                    return value;
                }

                // by default decode
                if (!StringUtils.isNullOrEmptyTrimmed(value)) {
                    try {
                        return URLDecoder.decode(value, "UTF-8");
                    } catch (UnsupportedEncodingException e) {
                        log.warn("Failed to decode query: " + value, e);
                    }
                }

                return value;

            case cookie:
                Cookie cookie = context.getCookie(param.getName());
                return cookie == null ? null : cookie.getValue();

            case form:
                return context.request().getFormAttribute(param.getName());

            case matrix:
                return getMatrixParam(context.request(), param.getName());

            case header:
                return context.request().getHeader(param.getName());

            case body:
                return context.getBodyAsString();

            default:
                return null;
        }
    }

    private static ValueReader getValueReader(InjectionProvider provider,
                                              MethodParameter parameter,
                                              RouteDefinition definition,
                                              RoutingContext context,
                                              ReaderFactory
                                                      readers) {

        // get associated reader set in parameter
        if (parameter.isBody()) {
            return readers.get(parameter, parameter.getReader(), provider, context, definition.getConsumes());
        } else {
            return readers.get(parameter, parameter.getReader(), provider, context);
        }
    }

    private static String getParam(String mountPoint, HttpServerRequest request, int index) {

        String param = request.getParam("param" + index); // default mount of params without name param0, param1 ...
        if (param == null) { // failed to get directly ... try from request path

            String path = removeMountPoint(mountPoint, request.path());

            String[] items = path.split("/");
            if (index >= 0 && index < items.length) { // simplistic way to find param value from path by index
                return items[index];
            }
        }

        return null;
    }

    private static String removeMountPoint(String mountPoint, String path) {

        if (StringUtils.isNullOrEmptyTrimmed(mountPoint)) {
            return path;
        }

        return path.substring(mountPoint.length());
    }

    /**
     * Removes matrix params from path
     *
     * @param path       to clean up
     * @param definition to check if matrix params are present
     * @return cleaned up path
     */
    private static String removeMatrixFromPath(String path, RouteDefinition definition) {

        // simple removal ... we don't care what matrix attributes were given
        if (definition.hasMatrixParams()) {
            int index = path.indexOf(";");
            if (index > 0) {
                return path.substring(0, index);
            }
        }

        return path;
    }

    /**
     * @param request to extract matrix parameter from (URL)
     * @param name    of desired matrix parameter
     * @return found parameter value or null if none found
     */
    // TODO: this might be slow at times ... pre-parse matrix into hash map ... and store
    private static String getMatrixParam(HttpServerRequest request, String name) {

        // get URL ... and find ;name=value pair
        String url = request.uri();
        String[] items = url.split(";");
        for (String item : items) {
            String[] nameValue = item.split("=");
            if (nameValue.length == 2 && nameValue[0].equals(name)) {
                return nameValue[1];
            }
        }

        return null;
    }
}
