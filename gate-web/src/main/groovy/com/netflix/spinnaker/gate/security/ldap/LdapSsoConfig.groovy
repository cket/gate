/*
 * Copyright 2017 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.gate.security.ldap

import com.netflix.spinnaker.gate.security.AllowedAccountsSupport
import com.netflix.spinnaker.gate.security.AuthConfig
import com.netflix.spinnaker.gate.security.SpinnakerAuthConfig
import com.netflix.spinnaker.gate.services.PermissionService
import com.netflix.spinnaker.security.User
import org.apache.commons.lang3.StringUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.ldap.core.DirContextAdapter
import org.springframework.ldap.core.DirContextOperations
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.builders.WebSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.ldap.userdetails.UserDetailsContextMapper
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint
import org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter
import org.springframework.stereotype.Component

import org.springframework.context.annotation.Bean
import org.springframework.security.core.AuthenticationException
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import org.springframework.security.web.authentication.ui.DefaultLoginPageGeneratingFilter
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import com.netflix.spinnaker.gate.config.Service
import com.netflix.spinnaker.gate.config.ServiceConfiguration
import org.springframework.beans.factory.annotation.Value

@ConditionalOnExpression('${ldap.enabled:false}')
@Configuration
@SpinnakerAuthConfig
@EnableWebSecurity
@Order(Ordered.LOWEST_PRECEDENCE)
class LdapSsoConfig extends WebSecurityConfigurerAdapter {

  @Autowired
  AuthConfig authConfig

  @Autowired
  LdapConfigProps ldapConfigProps

  @Autowired
  ExternalSslAwareEntryPoint entryPoint

  @Autowired
  LdapUserContextMapper ldapUserContextMapper

  @Autowired
  DefaultLoginPageGeneratingFilter defaultFilter

  @Autowired(required = false)
  List<LdapSsoConfigurer> configurers

  @Bean
  DefaultLoginPageGeneratingFilter defaultFilter() {
    new DefaultLoginPageGeneratingFilter(new org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter())
  }

  @Override
  protected void configure(AuthenticationManagerBuilder auth) throws Exception {
    def ldapConfigurer =
        auth.ldapAuthentication()
            .contextSource()
              .url(ldapConfigProps.url)
              .managerDn(ldapConfigProps.managerDn)
              .managerPassword(ldapConfigProps.managerPassword)
            .and()
            .rolePrefix("")
            .groupSearchBase(ldapConfigProps.groupSearchBase)
            .userDetailsContextMapper(ldapUserContextMapper)

    if (ldapConfigProps.userDnPattern) {
      ldapConfigurer.userDnPatterns(ldapConfigProps.userDnPattern)
    }

    if (ldapConfigProps.userSearchBase) {
      ldapConfigurer.userSearchBase(ldapConfigProps.userSearchBase)
    }

    if (ldapConfigProps.userSearchFilter) {
      ldapConfigurer.userSearchFilter(ldapConfigProps.userSearchFilter)
    }
  }

  @Override
  protected void configure(HttpSecurity http) throws Exception {
    http.formLogin()

    // http.requiresChannel().anyRequest().requiresSecure()
      // .loginPage('/login')
      // this still used http
      // this tells us it isn't a problem inherent in DefaultLoginPageGeneratingFilter
      // interestingly, if we get add this page then that filter is disabled.
    authConfig.configure(http)

    // use this to force https via entrypoint
    http.exceptionHandling().authenticationEntryPoint(entryPoint)

    // tell our page to visit /login on post (uses /null otherwise)
    defaultFilter.setAuthenticationUrl('/login')
    // now enable the page by inserting in chain
    http.addFilterBefore(defaultFilter, AbstractPreAuthenticatedProcessingFilter.class)

    configurers?.each {
        it.configure(http)
    }
  }

  @Override
  void configure(WebSecurity web) throws Exception {
    authConfig.configure(web)
  }

  @Component
  static class LdapUserContextMapper implements UserDetailsContextMapper {

    @Autowired
    PermissionService permissionService

    @Autowired
    AllowedAccountsSupport allowedAccountsSupport

    @Override
    UserDetails mapUserFromContext(DirContextOperations ctx, String username, Collection<? extends GrantedAuthority> authorities) {
      def roles = sanitizeRoles(authorities)
      permissionService.loginWithRoles(username, roles)

      return new User(username: username,
                      email: ctx.getStringAttribute("mail"),
                      roles: roles,
                      allowedAccounts: allowedAccountsSupport.filterAllowedAccounts(username, roles))
    }

    @Override
    void mapUserToContext(UserDetails user, DirContextAdapter ctx) {
      throw new UnsupportedOperationException("Cannot save to LDAP server")
    }

    private static Set<String> sanitizeRoles(Collection<? extends GrantedAuthority> authorities) {
      authorities.findResults {
        StringUtils.removeStartIgnoreCase(it.authority, "ROLE_")?.toLowerCase()
      }
    }
  }

  @Component
  @ConfigurationProperties("ldap")
  static class LdapConfigProps {
    String url
    String managerDn
    String managerPassword
    String groupSearchBase

    String userDnPattern
    String userSearchBase
    String userSearchFilter
  }

  @Component
  @ConditionalOnExpression('${ldap.enabled:false}')
  static class ExternalSslAwareEntryPoint extends LoginUrlAuthenticationEntryPoint {

    @Autowired
    ExternalSslAwareEntryPoint(@Value('${services.deck.baseUrl}') String gateBaseUrl) {
      super('/login')
      if (gateBaseUrl.startsWith('https')){
        this.setForceHttps(true)
      }
    }
  }
}
