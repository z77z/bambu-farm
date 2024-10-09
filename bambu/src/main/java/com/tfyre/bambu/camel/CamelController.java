package com.tfyre.bambu.camel;

import com.tfyre.bambu.BambuConfig;
import com.tfyre.bambu.BambuConfig.Printer;
import com.tfyre.bambu.CloudService;
import com.tfyre.bambu.printer.BambuPrinters;
import com.tfyre.bambu.mqtt.AbstractMqttController;
import com.tfyre.bambu.printer.BambuPrinterException;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.apache.camel.CamelContext;
import org.apache.camel.Endpoint;
import org.apache.camel.StartupListener;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

/**
 *
 * @author Francois Steyn - (fsteyn@tfyre.co.za)
 */
@Startup
@ApplicationScoped
public class CamelController extends AbstractMqttController implements StartupListener {

    @Inject
    BambuConfig config;
    @Inject
    CloudService cloud;
    @Inject
    Logger log;

    @Inject
    BambuPrinters printers;

    @Inject
    ManagedExecutor executor;

    Optional<CloudService.Data> cloudData = Optional.empty();

    @Override
    public void onCamelContextStarted(final CamelContext context, final boolean alreadyStarted) throws Exception {

    }

    @Override
    public void onCamelContextFullyStarted(final CamelContext context, final boolean alreadyStarted) throws Exception {
        executor.submit(() -> {
            try {
                printers.startPrinters();
            } catch (BambuPrinterException ex) {
                log.errorf(ex, "onCamelContextFullyStarted: %s", ex.getMessage());
            }
        });
    }

    @Override
    public void configure() throws Exception {
        getCamelContext().addStartupListener(this);
        cloudData = cloud.getLoginData();
        config.printers().forEach(this::configurePrinter);
        log.info("configured");
    }

    private String getUrl(final Printer config) {
        return config.mqtt().url().orElseGet(() -> "ssl://%s:%d".formatted(config.ip(), config.mqtt().port()));
    }

    private Endpoint getMqttEndpoint(final String topic, final Printer printerConfig) {
        return cloudData
                .map(data -> getMqttEndpoint(topic, config.cloud().url(), data.username(), data.password()))
                .orElseGet(() -> getMqttEndpoint(topic, getUrl(printerConfig), printerConfig.username(), printerConfig.accessCode()));
    }

    private void configurePrinter(final String id, final Printer config) {
        final String name = config.name().orElse(id);
        if (!config.enabled()) {
            log.infof("Skipping: id[%s] as name[%s]", id, name);
            return;
        }
        log.infof("Configuring: id[%s] as name[%s]", id, name);
        final String producerTopic = getTopic(config.mqtt().requestTopic(), config.deviceId(), "request");
        final String consumerTopic = getTopic(config.mqtt().reportTopic(), config.deviceId(), "report");
        final Endpoint producer = getMqttEndpoint(producerTopic, config);
        final Endpoint consumer = getMqttEndpoint(consumerTopic, config);
        final Endpoint printer = getPrinterEndpoint(name);

        final BambuPrinters.PrinterDetail detail = printers.newPrinter(id, name, config, printer);

        //producer
        from(printer)
                .id("producer-%s".formatted(name))
                .autoStartup(false)
                .group(name)
                .to(producer);
        //consumer
        from(consumer)
                .id("consumer-%s".formatted(name))
                .autoStartup(false)
                .group(name)
                .process(detail.processor());
    }

}
