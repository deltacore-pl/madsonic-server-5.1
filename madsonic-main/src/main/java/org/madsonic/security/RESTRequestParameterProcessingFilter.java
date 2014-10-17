/*
 This file is part of Subsonic.

 Subsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Subsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Subsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2009 (C) Sindre Mehus
 */
package org.madsonic.security;

import static org.madsonic.util.Base64Util.decode;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.madsonic.Logger;
import org.madsonic.controller.RESTController;
import org.madsonic.domain.Version;
import org.madsonic.service.SettingsService;
import org.madsonic.util.StringUtil;
import org.madsonic.util.XMLBuilder;
import org.apache.commons.lang.StringUtils;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.web.bind.ServletRequestUtils;

/**
 * Performs authentication based on credentials being present in the HTTP request parameters. Also checks
 * API versions and license information.
 * <p/>
 * The username should be set in parameter "u", and the password should be set in parameter "p".
 * The REST protocol version should be set in parameter "v".
 *
 * The password can either be in plain text or be UTF-8 hexencoded preceded by "enc:".
 *
 * @author Sindre Mehus
 */
public class RESTRequestParameterProcessingFilter implements Filter {

    private static final Logger LOG = Logger.getLogger(RESTRequestParameterProcessingFilter.class);

    private ProviderManager authenticationManager;
    private SettingsService settingsService;
    
    /**
     * {@inheritDoc}
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {

    	if (!(request instanceof HttpServletRequest)) {
            throw new ServletException("Can only process HttpServletRequest");
        }
        if (!(response instanceof HttpServletResponse)) {
            throw new ServletException("Can only process HttpServletResponse");
        }

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        
        String username = StringUtils.trimToNull(httpRequest.getParameter("u"));
        String password = decrypt(StringUtils.trimToNull(httpRequest.getParameter("p")));
        String version = StringUtils.trimToNull(httpRequest.getParameter("v"));
        String client = StringUtils.trimToNull(httpRequest.getParameter("c"));

        if (username == null && password == null) {
            String header = httpRequest.getHeader("Authorization");
            if (header != null && header.startsWith("Basic ")) {
                String basicAuth = new String(decode(header.substring("Basic ".length())));
                username = basicAuth.substring(0, basicAuth.indexOf(':'));
                password = basicAuth.substring(1 + basicAuth.indexOf(':'));
            }

        }
        RESTController.ErrorCode errorCode = null;

        // The username and password parameters are not required if the user
        // was previously authenticated, for example using Basic Auth.
        Authentication previousAuth = SecurityContextHolder.getContext().getAuthentication();
        if (previousAuth == null) {
            if (username == null || password == null) {
                errorCode = RESTController.ErrorCode.MISSING_PARAMETER;
            }
        } else {
            if (username != null || password != null) {
                LOG.warn("Username and password provided in URL params, but discarded. User already authenticated as "
                        + previousAuth.getName());
            }
            username = previousAuth.getName();
        }


        if (version == null) { // || client == null) {
            errorCode = RESTController.ErrorCode.MISSING_PARAMETER;
        }

        if (errorCode == null) {
            errorCode = checkAPIVersion(version);
        }

        if (errorCode == null && previousAuth == null) {
            errorCode = authenticate(username, password, previousAuth);
        }

        if (errorCode == null) {
            chain.doFilter(request, response);
        } else {
            LOG.info("Authentication failed for user " + username);

            SecurityContextHolder.getContext().setAuthentication(null);
            sendErrorXml(httpRequest, httpResponse, errorCode);
        }
    }

    private RESTController.ErrorCode checkAPIVersion(String version) {
        Version serverVersion = new Version(StringUtil.getRESTProtocolVersion());
        Version clientVersion = new Version(version);

        if (serverVersion.getMajor() > clientVersion.getMajor()) {
            return RESTController.ErrorCode.PROTOCOL_MISMATCH_CLIENT_TOO_OLD;
        } else if (serverVersion.getMajor() < clientVersion.getMajor()) {
            return RESTController.ErrorCode.PROTOCOL_MISMATCH_SERVER_TOO_OLD;
        } else if (serverVersion.getMinor() < clientVersion.getMinor()) {
            return RESTController.ErrorCode.PROTOCOL_MISMATCH_SERVER_TOO_OLD;
        }
        return null;
    }

    private RESTController.ErrorCode authenticate(String username, String password, Authentication previousAuth) {

        // Previously authenticated and username not overridden?
        if (username == null && previousAuth != null) {
            return null;
        }

        // Ensure password is given.
        if (password == null) {
            return RESTController.ErrorCode.MISSING_PARAMETER;
        }

        try {
            UsernamePasswordAuthenticationToken authRequest = new UsernamePasswordAuthenticationToken(username, password);
            Authentication authResult = authenticationManager.authenticate(authRequest);
            SecurityContextHolder.getContext().setAuthentication(authResult);
//            LOG.info("Authentication succeeded for user " + username);
        } catch (AuthenticationException x) {
            LOG.warn("Authentication failed for user " + username);
            return RESTController.ErrorCode.NOT_AUTHENTICATED;
        }
        return null;
    }

    public static String decrypt(String s) {
        if (s == null) {
            return null;
        }
        if (!s.startsWith("enc:")) {
            return s;
        }
        try {
            return StringUtil.utf8HexDecode(s.substring(4));
        } catch (Exception e) {
            return s;
        }
    }

    private void sendErrorXml(HttpServletRequest request, HttpServletResponse response, RESTController.ErrorCode errorCode) throws IOException {
        String format = ServletRequestUtils.getStringParameter(request, "f", "xml");
        boolean json = "json".equals(format);
        boolean jsonp = "jsonp".equals(format);
        XMLBuilder builder;

        response.setCharacterEncoding(StringUtil.ENCODING_UTF8);

        if (json) {
            builder = XMLBuilder.createJSONBuilder();
            response.setContentType("application/json");
        } else if (jsonp) {
            builder = XMLBuilder.createJSONPBuilder(request.getParameter("callback"));
            response.setContentType("text/javascript");
        } else {
        	builder = XMLBuilder.createXMLBuilder();
            response.setContentType("text/xml");
        }

        builder.preamble(StringUtil.ENCODING_UTF8);
        builder.add("subsonic-response", false,
                    new XMLBuilder.Attribute("xmlns", "http://madsonic.org/restapi"),
                    new XMLBuilder.Attribute("status", "failed"),
					new XMLBuilder.Attribute("type", "madsonic"),					
                    new XMLBuilder.Attribute("version", StringUtil.getRESTProtocolVersion()));

        builder.add("error", true,
                    new XMLBuilder.Attribute("code", errorCode.getCode()),
                    new XMLBuilder.Attribute("message", errorCode.getMessage()));
        builder.end();
        response.getWriter().print(builder);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void destroy() {
    }

    public void setAuthenticationManager(ProviderManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

	public void setSettingsService(SettingsService settingsService) {
		this.settingsService = settingsService;
	}

}
