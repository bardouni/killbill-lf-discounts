package com.lightfunnels.killbill.discounts;

import java.util.Hashtable;

import org.killbill.billing.invoice.plugin.api.InvoicePluginApi;
import org.killbill.billing.osgi.api.OSGIPluginProperties;
import org.killbill.billing.osgi.libs.killbill.KillbillActivatorBase;
import org.osgi.framework.BundleContext;

public class DiscountsActivator extends KillbillActivatorBase {

    // the directory name under bundles/plugins/java, listed in org.killbill.invoice.plugin
    public static final String PLUGIN_NAME = "lf-discounts";

    @Override
    public void start(final BundleContext context) throws Exception {
        super.start(context);

        final Hashtable<String, String> props = new Hashtable<>();
        props.put(OSGIPluginProperties.PLUGIN_NAME_PROP, PLUGIN_NAME);
        registrar.registerService(context, InvoicePluginApi.class, new DiscountsInvoicePluginApi(killbillAPI, configProperties, clock.getClock()), props);
    }
}
