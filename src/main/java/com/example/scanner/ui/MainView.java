package com.example.scanner.ui;

import com.example.scanner.core.NetworkScanner.DeviceInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinServletRequest;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@Route("")
public class MainView extends VerticalLayout {

    private final TextField cidr = new TextField("Subnet (CIDR)");
    private final IntegerField timeout = new IntegerField("Ping timeout (ms)");
    private final Button scanBtn = new Button("Scan");
    private final ProgressBar progress = new ProgressBar();
    private final Grid<DeviceInfo> grid = new Grid<>(DeviceInfo.class, false);

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    private ScheduledExecutorService poller;
    private volatile String currentJob;

    public MainView() {
        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(new Text("Local Network Device Scanner (Quarkus + Vaadin)"));

        cidr.setPlaceholder("e.g. 192.168.0.0/24");
        cidr.setValue("192.168.0.0/24");
        cidr.setWidth("260px");

        timeout.setValue(300);
        timeout.setMin(50);
        timeout.setMax(5000);
        timeout.setWidth("180px");

        scanBtn.addClickListener(e -> startScan());

        progress.setMin(0);
        progress.setMax(100);
        progress.setValue(0);
        progress.setVisible(false);
        progress.setWidth("300px");

        HorizontalLayout controls = new HorizontalLayout(cidr, timeout, scanBtn, progress);
        controls.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.END);
        add(controls);

        grid.addColumn(DeviceInfo::ip)
                .setHeader("IP")
                .setAutoWidth(true)
                .setSortable(true);

        grid.addColumn(DeviceInfo::hostname)
                .setHeader("Hostname")
                .setFlexGrow(1)
                .setSortable(true);

        grid.addColumn(d -> d.reachable() ? "Yes" : "No")
                .setHeader("Reachable")
                .setAutoWidth(true)
                .setSortable(true);

        grid.addColumn(DeviceInfo::pingMillis)
                .setHeader("Ping (ms)")
                .setAutoWidth(true)
                .setSortable(true);
        grid.setHeight("70vh");
        add(grid);
        expand(grid);
    }

    private void startScan() {
        if (poller != null) {
            poller.shutdownNow();
            poller = null;
        }
        progress.setValue(0);
        progress.setVisible(true);
        scanBtn.setEnabled(false);
        grid.setItems(Collections.emptyList());

        String origin = getOrigin();
        if (origin == null) {
            Notification.show("No servlet request context. Reload the page.", 3000, Notification.Position.MIDDLE);
            scanBtn.setEnabled(true);
            progress.setVisible(false);
            return;
        }

        String qCidr = url(cidr.getValue());
        int t = timeout.getValue() != null ? timeout.getValue() : 300;

        HttpRequest req = HttpRequest.newBuilder(URI.create(origin + "/api/scan/start?cidr=" + qCidr + "&timeoutMs=" + t))
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(5))
                .build();

        CompletableFuture.runAsync(() -> {
            try {
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() != 200) {
                    uiNotify("Scan start failed (HTTP " + res.statusCode() + ")", true);
                    return;
                }
                Map<?, ?> m = mapper.readValue(res.body(), Map.class);
                currentJob = String.valueOf(m.get("jobId"));
                startPolling(origin, currentJob);
            } catch (Exception ex) {
                uiNotify("Scan start failed: " + ex.getMessage(), true);
            }
        });
    }

    private void startPolling(String origin, String jobId) {
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "scan-poller");
            t.setDaemon(true);
            return t;
        });

        var req = HttpRequest.newBuilder()
                .timeout(Duration.ofSeconds(4))
                .GET();

        poller.scheduleAtFixedRate(() -> {
            try {
                HttpResponse<String> res = http.send(
                        req.uri(URI.create(origin + "/api/scan/progress/" + jobId)).build(),
                        HttpResponse.BodyHandlers.ofString()
                );
                if (res.statusCode() == 404) {
                    uiNotify("Scan job expired.", true);
                    return;
                }
                if (res.statusCode() != 200) {
                    uiNotify("Progress HTTP " + res.statusCode(), true);
                    return;
                }
                ProgressDTO dto = mapper.readValue(res.body(), ProgressDTO.class);

                getUI().ifPresent(ui -> ui.access(() -> {
                    progress.setValue(dto.percent);
                    if (dto.results != null) grid.setItems(dto.results);
                    if (dto.finished) {
                        progress.setValue(100);
                        progress.setVisible(false);
                        scanBtn.setEnabled(true);
                        Notification.show("Scan finished. Found " + (dto.results == null ? 0 : dto.results.size()) + " device(s).",
                                2500, Notification.Position.TOP_CENTER);
                        stopPolling();
                    }
                }));
            } catch (Exception ex) {
                uiNotify("Polling failed: " + ex.getMessage(), true);
            }
        }, 0, 400, TimeUnit.MILLISECONDS);
    }

    private void stopPolling() {
        if (poller != null) {
            poller.shutdownNow();
            poller = null;
        }
        currentJob = null;
    }

    private void uiNotify(String msg, boolean reset) {
        getUI().ifPresent(ui -> ui.access(() -> {
            Notification.show(msg, 3500, Notification.Position.MIDDLE);
            if (reset) {
                scanBtn.setEnabled(true);
                progress.setVisible(false);
                stopPolling();
            }
        }));
    }

    private static String url(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String getOrigin() {
        var vr = VaadinRequest.getCurrent();
        if (!(vr instanceof VaadinServletRequest vsr)) return null;
        String scheme = vsr.getHttpServletRequest().getScheme();
        String host = vsr.getHttpServletRequest().getServerName();
        int port = vsr.getHttpServletRequest().getServerPort();
        boolean defaultPort = (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
        return scheme + "://" + host + (defaultPort ? "" : ":" + port);
    }

    // ----- DTOs for parsing progress JSON -----

    public static class ProgressDTO {
        public int percent;
        public boolean finished;
        public List<DeviceInfo> results;
    }
}