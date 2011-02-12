/**
 * Licensed to Jasig under one or more contributor license
 * agreements. See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Jasig licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.jasig.cas.server;

import com.github.inspektr.audit.annotation.Audit;
import org.jasig.cas.server.authentication.*;
import org.jasig.cas.server.login.*;
import org.jasig.cas.server.logout.DefaultLogoutResponseImpl;
import org.jasig.cas.server.logout.LogoutRequest;
import org.jasig.cas.server.logout.LogoutResponse;
import org.jasig.cas.server.session.*;
import org.perf4j.aop.Profiled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.Assert;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.util.*;

/**
 * Concrete implementation of a CentralAuthenticationService, and also the
 * central, organizing component of CAS's internal implementation.
 * <p>
 * This class is thread safe.
 * <p>
 * This class has the following properties that must be set:
 * <ul>
 * <li> <code>ticketRegistry</code> - The Ticket Registry to maintain the list
 * of available tickets.</li>
 * <li> <code>serviceTicketRegistry</code> - Provides an alternative to configure separate registries for TGTs and ST in order to store them
 * in different locations (i.e. long term memory or short-term)</li>
 * <li> <code>authenticationManager</code> - The service that will handle
 * authentication.</li>
 * <li> <code>ticketGrantingTicketUniqueTicketIdGenerator</code> - Plug in to
 * generate unique secure ids for TicketGrantingTickets.</li>
 * <li> <code>serviceTicketUniqueTicketIdGenerator</code> - Plug in to
 * generate unique secure ids for ServiceTickets.</li>
 * <li> <code>ticketGrantingTicketExpirationPolicy</code> - The expiration
 * policy for TicketGrantingTickets.</li>
 * <li> <code>serviceTicketExpirationPolicy</code> - The expiration policy for
 * ServiceTickets.</li>
 * </ul>
 * 
 * @author William G. Thompson, Jr.
 * @author Scott Battaglia
 * @author Dmitry Kopylenko
 * @version $Revision: 1.16 $ $Date: 2007/04/24 18:11:36 $
 * @since 3.0
 */
@Named("centralAuthenticationService")
@Singleton
public final class DefaultCentralAuthenticationServiceImpl implements CentralAuthenticationService {

    /** Log instance for logging events, info, warnings, errors, etc. */
    private final Logger log = LoggerFactory.getLogger(this.getClass());

    /**
     * AuthenticationManager for authenticating credentials for purposes of
     * obtaining tickets.
     */
    @NotNull
    private final AuthenticationManager authenticationManager;

    @NotNull
    private final SessionStorage sessionStorage;

    @NotNull
    @Autowired(required = false)
    private List<PreAuthenticationPlugin> preAuthenticationPlugins = new ArrayList<PreAuthenticationPlugin>();

    @NotNull
    @Autowired(required = false)
    private List<AuthenticationResponsePlugin> authenticationResponsePlugins = new ArrayList<AuthenticationResponsePlugin>();

    @NotNull
    @Size(min = 1)
    private final List<ServiceAccessResponseFactory> serviceAccessResponseFactories;

    @NotNull
    private final ServicesManager servicesManager;

    @Inject
    public DefaultCentralAuthenticationServiceImpl(final AuthenticationManager authenticationManager, final SessionStorage sessionStorage, final List<ServiceAccessResponseFactory> serviceAccessResponseFactories, final ServicesManager servicesManager) {
        this.authenticationManager = authenticationManager;
        this.sessionStorage = sessionStorage;
        this.serviceAccessResponseFactories = serviceAccessResponseFactories;
        this.servicesManager = servicesManager;
    }

    @Audit(action="CREATE_SESSION", actionResolverName="CREATE_SESSION_RESOLVER", resourceResolverName="CREATE_SESSION_RESOURCE_RESOLVER")
    @Profiled(tag = "CREATE_SESSION", logFailuresSeparately = false)
    public LoginResponse login(final LoginRequest loginRequest) {
        Assert.notNull(loginRequest, "loginRequest cannot be null.");
        final AuthenticationRequest authenticationRequest = new DefaultAuthenticationRequestImpl(loginRequest.getCredentials(), loginRequest.isLongTermLoginRequest());

        for (final PreAuthenticationPlugin plugin : this.preAuthenticationPlugins) {
            final LoginResponse loginResponse = plugin.continueWithAuthentication(loginRequest);

            if (loginResponse != null) {
                return loginResponse;
            }
        }

        final AuthenticationResponse authenticationResponse = this.authenticationManager.authenticate(authenticationRequest);

        for (final AuthenticationResponsePlugin authenticationResponsePlugin : this.authenticationResponsePlugins) {
            authenticationResponsePlugin.handle(loginRequest, authenticationResponse);
        }

        if (authenticationResponse.succeeded()) {
            final Session session = this.sessionStorage.createSession(authenticationResponse);
            return new DefaultLoginResponseImpl(session, authenticationResponse);
        }

        return new DefaultLoginResponseImpl(authenticationResponse);
    }


    /**
     * Note, we only currently support this is on the top, user-initiated session.
     */
    @Audit(action="DESTROY_SESSION",actionResolverName="DESTROY_SESSION_RESOLVER",resourceResolverName="DESTROY_SESSION_RESOURCE_RESOLVER")
    @Profiled(tag = "DESTROY_SESSION",logFailuresSeparately = false)
    public LogoutResponse logout(final LogoutRequest logoutRequest) {
        final String sessionId = logoutRequest.getSessionId();

        if (sessionId == null || sessionId.isEmpty()) {
            return new DefaultLogoutResponseImpl();
        }

        final Session session = this.sessionStorage.destroySession(sessionId);

        if (session != null) {
            session.invalidate();
            return new DefaultLogoutResponseImpl(session);
        }

        return new DefaultLogoutResponseImpl();
    }

    @Audit(action="ADMIN_DESTROY_SESSIONS",actionResolverName="ADMIN_DESTROY_SESSIONS_RESOLVER",resourceResolverName="ADMIN_DESTROY_SESSIONS_RESOURCE_RESOLVER")
    @Profiled(tag = "ADMIN_DESTROY_SESSION",logFailuresSeparately = false)
    public LogoutResponse logout(final String userId) {
        Assert.notNull(userId, "userId cannot be null");
        final Set<Session> sessions = this.sessionStorage.findSessionsByPrincipal(userId);

        if (sessions.isEmpty()) {
            return new DefaultLogoutResponseImpl();
        }

        final Set<Session> destroyedSessions = new HashSet<Session>();

        for (final Session session : sessions) {
            final Session destroyedSession = this.sessionStorage.destroySession(session.getId());
            destroyedSessions.add(destroyedSession);
        }

        return new DefaultLogoutResponseImpl(destroyedSessions);
    }

    @Audit(action="VALIDATE_ACCESS",actionResolverName="VALIDATE_ACCESS_RESOLVER",resourceResolverName="VALIDATE_ACCESS_RESOURCE_RESOLVER")
    @Profiled(tag="VALIDATE_ACCESS",logFailuresSeparately = false)
    public ServiceAccessResponse validate(final TokenServiceAccessRequest tokenServiceAccessRequest) {
        Assert.notNull(tokenServiceAccessRequest, "tokenServiceAccessRequest cannot be null");

        if (!tokenServiceAccessRequest.isValid()) {
            log.debug("Token Validation request for {} was not valid a request.", tokenServiceAccessRequest);
            return findServiceAccessResponseFactory(tokenServiceAccessRequest).getServiceAccessResponse(tokenServiceAccessRequest);
        }

        final Session session = this.sessionStorage.findSessionByAccessId(tokenServiceAccessRequest.getToken());

        if (session == null) {
            log.debug("Token Validation request for {} resulted in session not found.", tokenServiceAccessRequest);
            return findServiceAccessResponseFactory(tokenServiceAccessRequest).getServiceAccessResponse(tokenServiceAccessRequest);
        }

        final Access access = session.getAccess(tokenServiceAccessRequest.getToken());

        if (access == null) {
            log.debug("Token Validation request for {} resulted in access not found.", tokenServiceAccessRequest);
           return findServiceAccessResponseFactory(tokenServiceAccessRequest).getServiceAccessResponse(session, null, null, Collections.<Access>emptyList());
        }

        if (!tokenServiceAccessRequest.getCredentials().isEmpty()) {
            final AuthenticationRequest request = new DefaultAuthenticationRequestImpl(tokenServiceAccessRequest.getCredentials(), false);
            final AuthenticationResponse response = this.authenticationManager.authenticate(request);

            if (response.succeeded()) {
                final Session delegatedSession = access.createDelegatedSession(response);
                this.sessionStorage.updateSession(delegatedSession);
            }
        }

        access.validate(tokenServiceAccessRequest);
        this.sessionStorage.updateSession(session);
        return findServiceAccessResponseFactory(access).getServiceAccessResponse(session, access, null, Collections.<Access>emptyList());
    }

    @Audit(action="ACCESS",actionResolverName="GRANT_ACCESS_RESOLVER",resourceResolverName="GRANT_ACCESS_RESOURCE_RESOLVER")
    @Profiled(tag="GRANT_ACCESS", logFailuresSeparately = false)
    public ServiceAccessResponse grantAccess(final ServiceAccessRequest serviceAccessRequest) throws SessionException, AccessException {
        Assert.notNull(serviceAccessRequest, "serviceAccessRequest cannot be null.");

        final boolean match = this.servicesManager.matchesExistingService(serviceAccessRequest);

        if (!match) {
            throw new UnauthorizedServiceException(String.format("Service [%s] not authorized to use CAS.", serviceAccessRequest.getServiceId()));
        }

        if (!serviceAccessRequest.isValid()) {
            return findServiceAccessResponseFactory(serviceAccessRequest).getServiceAccessResponse(serviceAccessRequest);
        }

        final Session session = this.sessionStorage.findSessionBySessionId(serviceAccessRequest.getSessionId());

        if (session == null) {
            if (serviceAccessRequest.isProxiedRequest()) {
                return findServiceAccessResponseFactory(serviceAccessRequest).getServiceAccessResponse(serviceAccessRequest);
            } else {
                throw new NotFoundSessionException(String.format("Session [%s] could not be found.", serviceAccessRequest.getSessionId()));
            }
        }

        if (!session.isValid()) {
            if (serviceAccessRequest.isProxiedRequest()) {
                return findServiceAccessResponseFactory(serviceAccessRequest).getServiceAccessResponse(serviceAccessRequest);
            } else {
                throw new InvalidatedSessionException(String.format("Session [%s] is no longer valid.", session.getId()));
            }
        }

        final Session sessionToWorkWith;
        final List<Access> remainingAccesses = new ArrayList<Access>();
        final AuthenticationResponse authenticationResponse;
        if (serviceAccessRequest.isForceAuthentication()) {
            // TODO we need to do all the steps, including the pre-auth ones above under login.
            final AuthenticationRequest authenticationRequest = new DefaultAuthenticationRequestImpl(serviceAccessRequest.getCredentials(), serviceAccessRequest.isLongTermLoginRequest());
            authenticationResponse = this.authenticationManager.authenticate(authenticationRequest);

            if (!authenticationResponse.succeeded()) {
                return findServiceAccessResponseFactory(serviceAccessRequest).getServiceAccessResponse(serviceAccessRequest, authenticationResponse);
            }

            if (!authenticationResponse.getPrincipal().equals(session.getPrincipal())) {
                // expire the existing session and get a new session
                final Session destroyedSession = this.sessionStorage.destroySession(session.getId());
                destroyedSession.invalidate();
                final LogoutResponse logoutResponse = new DefaultLogoutResponseImpl(destroyedSession);
                remainingAccesses.addAll(logoutResponse.getLoggedInAccesses());
                sessionToWorkWith = this.sessionStorage.createSession(authenticationResponse);

            } else {
                session.getAuthentications().addAll(authenticationResponse.getAuthentications());
                sessionToWorkWith = session;
            }
        } else {
            authenticationResponse = null;
            sessionToWorkWith = session;
        }

        final Access access = sessionToWorkWith.grant(serviceAccessRequest);
        this.sessionStorage.updateSession(sessionToWorkWith);

        return findServiceAccessResponseFactory(access).getServiceAccessResponse(sessionToWorkWith, access, authenticationResponse, remainingAccesses);
    }

    public void setPreAuthenticationPlugins(final List<PreAuthenticationPlugin> preAuthenticationPlugins) {
        this.preAuthenticationPlugins = preAuthenticationPlugins;
    }

    public void setAuthenticationResponsePlugins(final List<AuthenticationResponsePlugin> authenticationResponsePlugins) {
        this.authenticationResponsePlugins = authenticationResponsePlugins;
    }

    private ServiceAccessResponseFactory findServiceAccessResponseFactory(final ServiceAccessRequest serviceAccessRequest) {
        for (final ServiceAccessResponseFactory factory : this.serviceAccessResponseFactories) {
            if (factory.supports(serviceAccessRequest)) {
                return factory;
            }
        }

        throw new IllegalStateException(String.format("No ServiceAccessResponseFactory configured for ServiceAccessRequest of type %s", serviceAccessRequest.getClass().getSimpleName()));
    }

    private ServiceAccessResponseFactory findServiceAccessResponseFactory(final Access access) {
        for (final ServiceAccessResponseFactory factory : this.serviceAccessResponseFactories) {
            if (factory.supports(access)) {
                return factory;
            }
        }

        throw new IllegalStateException(String.format("No ServiceAccessResponseFactory configured for Access of type %s", access.getClass().getSimpleName()));
    }
}
