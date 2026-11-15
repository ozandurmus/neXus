package com.securityexpert.nexus.ui2.service.security;

import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;
import org.springframework.web.util.ServletRequestPathUtils;

import com.securityexpert.nexus.ui2.service.security.NexusRouteActionAuthorizationManager.HandlerKind;
import com.securityexpert.nexus.ui2.service.security.NexusRouteActionAuthorizationManager.HandlerKindResolver;

/**
 * Asks the application's {@link HandlerMapping}s, in the dispatcher's own order, which handler would serve the request, as
 * {@code DispatcherServlet} does before it runs an interceptor. A mapping that refuses the request (wrong method, unsupported
 * media type) or no mapping at all means no handler: the interceptor never ran in those cases and the dispatcher answers.
 * The attributes a mapping sets while resolving (best matching pattern, ...) are left on the request, exactly as they were when the
 * interceptor ran after the dispatcher's own lookup: the HTTP metrics filter labels a refused request by that pattern, and the
 * dispatcher overwrites them with the same values.
 */
public final class DispatcherHandlerKindResolver implements HandlerKindResolver {

    private final ObjectProvider<HandlerMapping> mappings;

    public DispatcherHandlerKindResolver(ObjectProvider<HandlerMapping> mappings) {
        this.mappings = mappings;
    }

    @Override
    public HandlerKind resolve(HttpServletRequest request) {
        boolean parsedHere = !ServletRequestPathUtils.hasParsedRequestPath(request);
        Object previousPath = request.getAttribute(ServletRequestPathUtils.PATH_ATTRIBUTE);
        try {
            if (parsedHere) ServletRequestPathUtils.parseAndCache(request);
            List<HandlerMapping> ordered = mappings.orderedStream().toList();
            for (HandlerMapping mapping : ordered) {
                HandlerExecutionChain chain = mapping.getHandler(request);
                if (chain != null) {
                    return chain.getHandler() instanceof ResourceHttpRequestHandler ? HandlerKind.RESOURCE
                            : HandlerKind.CONTROLLER;
                }
            }
            return HandlerKind.NONE;
        } catch (ServletException refusedByMapping) {
            // HttpRequestMethodNotSupportedException and its kin: the dispatcher answers this request itself.
            return HandlerKind.NONE;
        } catch (RuntimeException failure) {
            throw failure; // fail closed: a request this resolver cannot classify is never silently waved through
        } catch (Exception failure) {
            throw new IllegalStateException("handler lookup failed", failure);
        } finally {
            if (parsedHere) {
                if (previousPath == null) ServletRequestPathUtils.clearParsedRequestPath(request);
                else request.setAttribute(ServletRequestPathUtils.PATH_ATTRIBUTE, previousPath);
            }
        }
    }
}
