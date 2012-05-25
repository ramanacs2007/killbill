/*
 * Copyright 2010-2011 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.billing.jaxrs.resources;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;


import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import com.ning.billing.util.api.CustomFieldUserApi;
import com.ning.billing.util.dao.ObjectType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.ning.billing.ErrorCode;
import com.ning.billing.account.api.Account;
import com.ning.billing.account.api.AccountApiException;
import com.ning.billing.account.api.AccountData;
import com.ning.billing.account.api.AccountUserApi;
import com.ning.billing.entitlement.api.timeline.BundleTimeline;
import com.ning.billing.entitlement.api.timeline.EntitlementRepairException;
import com.ning.billing.entitlement.api.timeline.EntitlementTimelineApi;
import com.ning.billing.entitlement.api.user.EntitlementUserApi;
import com.ning.billing.entitlement.api.user.SubscriptionBundle;
import com.ning.billing.invoice.api.Invoice;
import com.ning.billing.invoice.api.InvoiceUserApi;
import com.ning.billing.jaxrs.json.AccountJson;
import com.ning.billing.jaxrs.json.AccountTimelineJson;
import com.ning.billing.jaxrs.json.BundleJsonNoSubscriptions;
import com.ning.billing.jaxrs.json.CustomFieldJson;
import com.ning.billing.jaxrs.util.Context;
import com.ning.billing.jaxrs.util.JaxrsUriBuilder;
import com.ning.billing.jaxrs.util.TagHelper;
import com.ning.billing.payment.api.PaymentApi;
import com.ning.billing.payment.api.PaymentApiException;
import com.ning.billing.payment.api.PaymentAttempt;
import com.ning.billing.util.api.TagUserApi;

@Singleton
@Path(JaxrsResource.ACCOUNTS_PATH)
public class AccountResource extends JaxRsResourceBase {

    private static final Logger log = LoggerFactory.getLogger(AccountResource.class);
    private static final String ID_PARAM_NAME = "accountId";
    private static final String CUSTOM_FIELD_URI = JaxrsResource.CUSTOM_FIELDS + "/{" + ID_PARAM_NAME + ":" + UUID_PATTERN + "}";
    private static final String TAG_URI = JaxrsResource.TAGS + "/{" + ID_PARAM_NAME + ":" + UUID_PATTERN + "}";

    private final AccountUserApi accountApi;
    private final EntitlementUserApi entitlementApi;
    private final EntitlementTimelineApi timelineApi;
    private final InvoiceUserApi invoiceApi;
    private final PaymentApi paymentApi;
    private final Context context;
    private final JaxrsUriBuilder uriBuilder;

    @Inject
    public AccountResource(final JaxrsUriBuilder uriBuilder,
            final AccountUserApi accountApi,
            final EntitlementUserApi entitlementApi, 
            final InvoiceUserApi invoiceApi,
            final PaymentApi paymentApi,
            final EntitlementTimelineApi timelineApi,
            final CustomFieldUserApi customFieldUserApi,
            final TagUserApi tagUserApi,
            final TagHelper tagHelper,
            final Context context) {
        super(uriBuilder, tagUserApi, tagHelper, customFieldUserApi);
        this.uriBuilder = uriBuilder;
    	this.accountApi = accountApi;
        this.entitlementApi = entitlementApi;
        this.invoiceApi = invoiceApi;
        this.paymentApi = paymentApi;
        this.timelineApi = timelineApi;
        this.context = context;
    }

    @GET
    @Path("/{accountId:" + UUID_PATTERN + "}")
    @Produces(APPLICATION_JSON)
    public Response getAccount(@PathParam("accountId") String accountId) {
        try {
            Account account = accountApi.getAccountById(UUID.fromString(accountId));

            AccountJson json = new AccountJson(account);
            return Response.status(Status.OK).entity(json).build();
        } catch (AccountApiException e) {
            return Response.status(Status.NO_CONTENT).build();            
        }
        
    }

    @GET
    @Path("/{accountId:" + UUID_PATTERN + "}/" + BUNDLES)
    @Produces(APPLICATION_JSON)
    public Response getAccountBundles(@PathParam("accountId") String accountId) {
        try {
            UUID uuid = UUID.fromString(accountId);
            accountApi.getAccountById(uuid);

            List<SubscriptionBundle> bundles = entitlementApi.getBundlesForAccount(uuid);
            Collection<BundleJsonNoSubscriptions> result = Collections2.transform(bundles, new Function<SubscriptionBundle, BundleJsonNoSubscriptions>() {
                @Override
                public BundleJsonNoSubscriptions apply(SubscriptionBundle input) {
                    return new BundleJsonNoSubscriptions(input);
                }
            });
            return Response.status(Status.OK).entity(result).build();
        } catch (AccountApiException e) {
            return Response.status(Status.NO_CONTENT).build();
        }
    }

    
    @GET
    @Produces(APPLICATION_JSON)
    public Response getAccountByKey(@QueryParam(QUERY_EXTERNAL_KEY) String externalKey) {
        try {
            Account account = null;
            if (externalKey != null) {
                account = accountApi.getAccountByKey(externalKey);
            }
            if (account == null) {
                return Response.status(Status.NO_CONTENT).build();
            }
            AccountJson json = new AccountJson(account);
            return Response.status(Status.OK).entity(json).build();
        } catch (AccountApiException e) {
            return Response.status(Status.NO_CONTENT).build();
        }
    }

    
    @POST
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response createAccount(final AccountJson json,
            @HeaderParam(HDR_CREATED_BY) final String createdBy,
            @HeaderParam(HDR_REASON) final String reason,
            @HeaderParam(HDR_COMMENT) final String comment) {

        try {
            AccountData data = json.toAccountData();
            final Account account = accountApi.createAccount(data, null, null, context.createContext(createdBy, reason, comment));
            return uriBuilder.buildResponse(AccountResource.class, "getAccount", account.getId());
        } catch (AccountApiException e) {
            final String error = String.format("Failed to create account %s", json);
            log.info(error, e);
            return Response.status(Status.BAD_REQUEST).entity(error).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Status.BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    @PUT
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    @Path("/{accountId:" + UUID_PATTERN + "}")
    public Response updateAccount(final AccountJson json,
            @PathParam("accountId") final String accountId,
            @HeaderParam(HDR_CREATED_BY) final String createdBy,
            @HeaderParam(HDR_REASON) final String reason,
            @HeaderParam(HDR_COMMENT) final String comment) {
        try {
            AccountData data = json.toAccountData();
            UUID uuid = UUID.fromString(accountId);
            accountApi.updateAccount(uuid, data, context.createContext(createdBy, reason, comment));
            return getAccount(accountId);
        } catch (AccountApiException e) {
        	if (e.getCode() == ErrorCode.ACCOUNT_DOES_NOT_EXIST_FOR_ID.getCode()) {
        		return Response.status(Status.NO_CONTENT).build();        		
        	} else {
        		log.info(String.format("Failed to update account %s with %s", accountId, json), e);
        		return Response.status(Status.BAD_REQUEST).build();
        	}
        } catch (IllegalArgumentException e) {
            return Response.status(Status.BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    // Not supported
    @DELETE
    @Path("/{accountId:" + UUID_PATTERN + "}")
    @Produces(APPLICATION_JSON)
    public Response cancelAccount(@PathParam("accountId") String accountId) {
        /*
        try {
            accountApi.cancelAccount(accountId);
            return Response.status(Status.NO_CONTENT).build();
        } catch (AccountApiException e) {
            log.info(String.format("Failed to cancel account %s", accountId), e);
            return Response.status(Status.BAD_REQUEST).build();
        }
       */
        return Response.status(Status.INTERNAL_SERVER_ERROR).build();
    }

    @GET
    @Path("/{accountId:" + UUID_PATTERN + "}/" + TIMELINE)
    @Produces(APPLICATION_JSON)
    public Response getAccountTimeline(@PathParam("accountId") String accountId) {
        try {
            Account account = accountApi.getAccountById(UUID.fromString(accountId));
           
            List<Invoice> invoices = invoiceApi.getInvoicesByAccount(account.getId());

            List<PaymentAttempt> payments = new LinkedList<PaymentAttempt>();

            if (invoices.size() > 0) {
                Collection<String> tmp = Collections2.transform(invoices, new Function<Invoice, String>() {
                    @Override
                    public String apply(Invoice input) {
                        return input.getId().toString();
                    }
                });
                List<String> invoicesId = new ArrayList<String>();
                invoicesId.addAll(tmp);
                for (String curId : invoicesId) {
                    payments.addAll(paymentApi.getPaymentAttemptsForInvoiceId(curId));
                }
            }

            List<SubscriptionBundle> bundles = entitlementApi.getBundlesForAccount(account.getId());
            List<BundleTimeline> bundlesTimeline = new LinkedList<BundleTimeline>();
            for (SubscriptionBundle cur : bundles) {
                bundlesTimeline.add(timelineApi.getBundleRepair(cur.getId()));
            }
            AccountTimelineJson json = new AccountTimelineJson(account, invoices, payments, bundlesTimeline);
            return Response.status(Status.OK).entity(json).build();
        } catch (AccountApiException e) {
            return Response.status(Status.NO_CONTENT).build();
        } catch (PaymentApiException e) {
            log.error(e.getMessage());
            return Response.status(Status.INTERNAL_SERVER_ERROR).build();
        } catch (EntitlementRepairException e) {
            log.error(e.getMessage());
            return Response.status(Status.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    @GET
    @Path(CUSTOM_FIELD_URI)
    @Produces(APPLICATION_JSON)
    public Response getCustomFields(@PathParam(ID_PARAM_NAME) final String id) {
        return super.getCustomFields(UUID.fromString(id));
    }

    @POST
    @Path(CUSTOM_FIELD_URI)
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response createCustomFields(@PathParam(ID_PARAM_NAME) final String id,
            final List<CustomFieldJson> customFields,
            @HeaderParam(HDR_CREATED_BY) final String createdBy,
            @HeaderParam(HDR_REASON) final String reason,
            @HeaderParam(HDR_COMMENT) final String comment) {
        return super.createCustomFields(UUID.fromString(id), customFields,
                context.createContext(createdBy, reason, comment));
    }
    
    @DELETE
    @Path(CUSTOM_FIELD_URI)
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response deleteCustomFields(@PathParam(ID_PARAM_NAME) final String id,
            @QueryParam(QUERY_CUSTOM_FIELDS) final String customFieldList,
            @HeaderParam(HDR_CREATED_BY) final String createdBy,
            @HeaderParam(HDR_REASON) final String reason,
            @HeaderParam(HDR_COMMENT) final String comment) {
        return super.deleteCustomFields(UUID.fromString(id), customFieldList,
                context.createContext(createdBy, reason, comment));
    }

    @GET
    @Path(TAG_URI)
    @Produces(APPLICATION_JSON)
    public Response getTags(@PathParam(ID_PARAM_NAME) String id) {
        return super.getTags(UUID.fromString(id));
    }

    @POST
    @Path(TAG_URI)
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response createTags(@PathParam(ID_PARAM_NAME) final String id,
            @QueryParam(QUERY_TAGS) final String tagList,
            @HeaderParam(HDR_CREATED_BY) final String createdBy,
            @HeaderParam(HDR_REASON) final String reason,
            @HeaderParam(HDR_COMMENT) final String comment) {
        return super.createTags(UUID.fromString(id), tagList,
                                context.createContext(createdBy, reason, comment));
    }

    @DELETE
    @Path(TAG_URI)
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response deleteTags(@PathParam(ID_PARAM_NAME) final String id,
            @QueryParam(QUERY_TAGS) final String tagList,
            @HeaderParam(HDR_CREATED_BY) final String createdBy,
            @HeaderParam(HDR_REASON) final String reason,
            @HeaderParam(HDR_COMMENT) final String comment) {

        return super.deleteTags(UUID.fromString(id), tagList,
                                context.createContext(createdBy, reason, comment));
    }

    @Override
    protected ObjectType getObjectType() {
        return ObjectType.ACCOUNT;
    }
}
