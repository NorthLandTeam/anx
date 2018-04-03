/**
 * Copyright (c) 2018 Cisco Systems
 *
 * Author: Steven Barth <stbarth@cisco.com>
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cisco.stbarth.netconf.anx;

import com.cisco.stbarth.netconf.xrgrpc.XRGRPCClient;
import com.cisco.stbarth.netconf.xrgrpc.XRGRPCClient.SubscriptionEncoding;
import com.cisco.stbarth.netconf.xrgrpc.XRGRPCClient.XRException;
import com.cisco.stbarth.netconf.xrgrpc.XRGRPCClient.XRGRPCClientSecurity;
import com.cisco.stbarth.netconf.anc.*;
import com.cisco.stbarth.netconf.anc.Netconf.Datastore;
import com.vaadin.data.provider.Query;
import com.vaadin.event.ShortcutAction;
import com.vaadin.icons.VaadinIcons;
import com.vaadin.ui.*;
import com.vaadin.ui.themes.ValoTheme;
import elemental.json.*;
import elemental.json.impl.JsonUtil;
import org.opendaylight.yangtools.yang.model.api.LeafListSchemaNode;
import org.opendaylight.yangtools.yang.model.api.LeafSchemaNode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.net.ssl.SSLHandshakeException;

/**
 * Telemetry support tools 
 */
public class TelemetryTools {
    private MainView view;
    private ComboBox<XMLElement> sensorGroupSelect;
    private static final String NS_TELEMETRY = "http://cisco.com/ns/yang/Cisco-IOS-XR-telemetry-model-driven-cfg";
    private static final String NS_EMS = "http://cisco.com/ns/yang/Cisco-IOS-XR-man-ems-cfg";

    TelemetryTools(MainView view) {
        this.view = view;
    }

    // Update sensor group selector with current sensor groups
    private void updateComponent() {
        Optional<String> selected = sensorGroupSelect.getSelectedItem().map(x -> x.getText("sensor-group-identifier"));
        sensorGroupSelect.setItems(getSensorGroups());
        sensorGroupSelect.setSelectedItem(null);
        sensorGroupSelect.getDataProvider().fetch(new Query<>())
                .filter(x -> x.getText("sensor-group-identifier").equals(selected.orElse(null)))
                .findAny().ifPresent(sensorGroupSelect::setSelectedItem);
    }

    // Render telemetry tools components for main view
    Component createComponent() {
        HorizontalLayout telemetryTools = new HorizontalLayout();
        telemetryTools.setSizeUndefined();
        telemetryTools.setDefaultComponentAlignment(Alignment.BOTTOM_LEFT);

        sensorGroupSelect = new ComboBox<>("Telemetry Tools");
        sensorGroupSelect.setIcon(VaadinIcons.DASHBOARD);
        sensorGroupSelect.setEmptySelectionCaption("Select or input sensor group name");
        sensorGroupSelect.setWidth("400px");
        sensorGroupSelect.setItemCaptionGenerator(x -> x.getText("sensor-group-identifier"));
        sensorGroupSelect.setNewItemHandler(name -> {
            XMLElement group = new XMLElement(NS_TELEMETRY, "sensor-group")
                    .withTextChild("sensor-group-identifier", name)
                    .withChild("sensor-paths");
            List<XMLElement> sensorGroups = sensorGroupSelect.getDataProvider().fetch(new Query<>())
                    .collect(Collectors.toList());
            sensorGroups.add(group);
            sensorGroupSelect.setItems(sensorGroups);
            sensorGroupSelect.setSelectedItem(group);
        });

        Button sensorGroupEdit = new Button("Edit group", VaadinIcons.EDIT);
        sensorGroupEdit.addClickListener(x -> showGroupEditor(sensorGroupSelect.getValue()));
        sensorGroupEdit.setEnabled(false);

        Button sensorGroupSubscribe = new Button("Live data", VaadinIcons.PIE_BAR_CHART);
        sensorGroupSubscribe.addClickListener(x -> showGroupSubscribe(sensorGroupSelect.getValue()));
        sensorGroupSubscribe.setEnabled(false);

        sensorGroupSelect.addValueChangeListener(x -> {
            sensorGroupEdit.setEnabled(!sensorGroupSelect.isEmpty());
            sensorGroupSubscribe.setEnabled(!sensorGroupSelect.isEmpty());
        });

        updateComponent();
        telemetryTools.addComponents(sensorGroupSelect, sensorGroupEdit, sensorGroupSubscribe);
        return telemetryTools;
    }

    // Query currently configured sensor-groups from device
    private Stream<XMLElement> getSensorGroups() {
        try (NetconfSession session = view.client.createSession()) {
            return session.getConfig(Netconf.Datastore.RUNNING,
                    Arrays.asList(new XMLElement(NS_TELEMETRY, "telemetry-model-driven")))
                    .getFirst(NS_TELEMETRY, "telemetry-model-driven").flatMap(x -> x.getFirst("sensor-groups"))
                    .map(x -> x.stream("sensor-group")).orElse(Stream.empty());
        } catch (NetconfException e) {
            Notification.show("Failed to read sensor groups: " + e.getMessage(), Notification.Type.TRAY_NOTIFICATION);
            return Stream.empty();
        }
    }

    private void showGroupEditor(XMLElement sensorGroup) {
        // Render sensor group editor and provide currently selected sensor group
        String groupID = sensorGroup.getText("sensor-group-identifier");
        Window editorWindow = new Window("Sensor group editor: ".concat(groupID));
        editorWindow.setModal(true);
        editorWindow.setResizable(false);
        editorWindow.setDraggable(false);
        editorWindow.setWidth("900px");
        editorWindow.setHeight("450px");

        Set<String> paths = sensorGroup.withoutNamespaces().find("sensor-paths/sensor-path/telemetry-sensor-path")
                .map(XMLElement::getText).collect(Collectors.toSet());

        VerticalLayout layout = new VerticalLayout();
        layout.setMargin(true);
        layout.setSizeFull();

        ListSelect<String> listSelect = new ListSelect<>();
        listSelect.setSizeFull();
        listSelect.setItems(paths);
        layout.addComponent(listSelect);
        layout.setExpandRatio(listSelect, 1.0f);

        Button removeButton = new Button("Remove path", VaadinIcons.ERASER);
        removeButton.setEnabled(false);
        removeButton.addClickListener(x -> {
            paths.removeAll(listSelect.getSelectedItems());
            listSelect.setItems(paths);
        });
        layout.addComponent(removeButton);
        layout.setComponentAlignment(removeButton, Alignment.MIDDLE_RIGHT);

        HorizontalLayout addLayout = new HorizontalLayout();
        addLayout.setWidth("100%");
        TextField path = new TextField();
        path.setWidth("100%");
        path.focus();

        listSelect.addSelectionListener(x -> {
            x.getFirstSelectedItem().ifPresent(path::setValue);
            removeButton.setEnabled(x.getFirstSelectedItem().isPresent());
        });

        Button addButton = new Button("Add path", VaadinIcons.PLUS);
        addButton.setEnabled(false);
        addButton.addClickListener(x -> {
            paths.add(path.getValue());
            listSelect.setItems(paths);
            path.clear();
            path.focus();
        });
        addButton.setClickShortcut(ShortcutAction.KeyCode.ENTER);
        addLayout.addComponent(path);
        addLayout.addComponent(addButton);
        addLayout.setExpandRatio(path, 1.0f);
        layout.addComponent(addLayout);

        path.addValueChangeListener(x -> addButton.setEnabled(x.getValue() != null));

        if (view.selectedNode != null) {
            WrappedYangNode node = view.selectedNode;
            if (node.getNode() instanceof LeafSchemaNode || node.getNode() instanceof LeafListSchemaNode)
                node = node.getParent();
            path.setValue(node.getSensorPath());
        }

        HorizontalLayout buttonLayout = new HorizontalLayout();
        Button saveButton = new Button("Save sensor group", VaadinIcons.CHECK_CIRCLE);
        saveButton.addStyleName(ValoTheme.BUTTON_FRIENDLY);
        saveButton.addClickListener(x -> {
            editorWindow.close();
            UI.getCurrent().removeWindow(editorWindow);
            XMLElement replace = new XMLElement(NS_TELEMETRY, "telemetry-model-driven")
                    .withChild("sensor-groups", g -> g.createChild("sensor-group")
                            .withTextChild("sensor-group-identifier", groupID)
                            //.withChild("enable")
                            .withAttribute(Netconf.NS_NETCONF, "operation", "replace")
                            .withChild("sensor-paths", p -> listSelect.getDataProvider().fetch(new Query<>())
                                    .forEach(sensorPath -> p.createChild("sensor-path")
                                            .withTextChild("telemetry-sensor-path", sensorPath))
                            )
                    );
            try (NetconfSession session = view.client.createSession()) {
                session.editConfig(Netconf.Datastore.CANDIDATE, replace);
                session.commit();
                updateComponent();
                Notification.show("Sensor group saved.", Notification.Type.TRAY_NOTIFICATION);
            } catch (NetconfException e) {
                Notification.show("Failed to save group: ".concat(e.getMessage()), Notification.Type.ERROR_MESSAGE);
                e.printStackTrace();
                if (e instanceof NetconfException.RPCException)
                    System.err.println(((NetconfException.RPCException)e).getRPCReply().toString());
            }
        });
        buttonLayout.addComponent(saveButton);
        buttonLayout.setComponentAlignment(saveButton, Alignment.MIDDLE_LEFT);

        Button deleteButton = new Button("Delete sensor group", VaadinIcons.ERASER);
        deleteButton.addStyleName(ValoTheme.BUTTON_DANGER);
        deleteButton.addClickListener(x -> {
            editorWindow.close();
            UI.getCurrent().removeWindow(editorWindow);
            XMLElement deletion = new XMLElement(NS_TELEMETRY, "telemetry-model-driven")
                    .withChild("sensor-groups", g -> g.createChild("sensor-group")
                            .withTextChild("sensor-group-identifier", groupID)
                            .withAttribute(Netconf.NS_NETCONF, "operation", "remove"));
            try (NetconfSession session = view.client.createSession()) {
                session.editConfig(Netconf.Datastore.CANDIDATE, deletion, Netconf.EditConfigDefaultOperation.NONE);
                session.commit();
                updateComponent();
                Notification.show("Sensor group deleted.", Notification.Type.TRAY_NOTIFICATION);
            } catch (NetconfException e) {
                Notification.show("Failed to delete group: ".concat(e.getMessage()), Notification.Type.ERROR_MESSAGE);
                e.printStackTrace();
                if (e instanceof NetconfException.RPCException)
                    System.err.println(((NetconfException.RPCException)e).getRPCReply().toString());
            }
        });
        buttonLayout.addComponent(deleteButton);
        buttonLayout.setComponentAlignment(deleteButton, Alignment.MIDDLE_RIGHT);

        layout.addComponent(buttonLayout);
        editorWindow.setContent(layout);
        UI.getCurrent().addWindow(editorWindow);
    }

    private Window showLoadingWindow(String caption) {
        Window loadingWindow = new Window();
        loadingWindow.setModal(true);
        loadingWindow.setResizable(false);
        loadingWindow.setClosable(false);
        loadingWindow.setDraggable(false);
        loadingWindow.setWidth("900px");
        loadingWindow.setHeight("75px");

        HorizontalLayout layout = new HorizontalLayout();
        layout.setMargin(true);
        layout.setSizeFull();

        ProgressBar progressBar = new ProgressBar();
        progressBar.setIndeterminate(true);
        progressBar.setWidth("150px");
        Label label = new Label(caption);
        label.addStyleName(ValoTheme.LABEL_BOLD);
        layout.addComponents(progressBar, label);
        layout.setComponentAlignment(progressBar, Alignment.MIDDLE_LEFT);
        layout.setComponentAlignment(label, Alignment.MIDDLE_LEFT);
        layout.setExpandRatio(label, 1.0f);

        loadingWindow.setContent(layout);

        UI.getCurrent().addWindow(loadingWindow);
        UI.getCurrent().push();
        return loadingWindow;
    }

    private void showGroupSubscribe(XMLElement sensorGroup) {
        // Render sensor group subscription

        String groupID = sensorGroup.getText("sensor-group-identifier");
        String subscriptionID = String.format("anx-%d", System.currentTimeMillis());
        int grpcPort = 57400;

        XMLElement subscriptionConfig = new XMLElement(NS_TELEMETRY, "telemetry-model-driven")
                .withChild("subscriptions", x -> x.createChild("subscription")
                        .withTextContent("subscription-identifier", subscriptionID)
                        .createChild("sensor-profiles").createChild("sensor-profile")
                                .withTextContent("sample-interval", "15000")
                                .withTextContent("sensorgroupid", groupID));
        XMLElement grpcConfig = new XMLElement(NS_EMS, "grpc").withChild("enable");

        Window loadingWindow = showLoadingWindow("Configuring GRPC and Telemetry subscription...");

        try (NetconfSession session = view.client.createSession()) {
            XMLElement grpc = session.getConfig(Netconf.Datastore.RUNNING,
                    Arrays.asList(new XMLElement(NS_EMS, "grpc"))).getOrEmpty(NS_EMS, "grpc");
            grpcPort = Integer.parseInt(grpc.getText("port"));

            session.editConfig(Datastore.CANDIDATE, grpcConfig);
            session.editConfig(Datastore.CANDIDATE, subscriptionConfig);
            session.commit();

            // If GRPC was not enabled, disable it afterwards
            if (!grpc.getFirst("enable").isPresent())
                grpcConfig.getOrEmpty("enable").withAttribute(Netconf.NS_NETCONF, "operation", "remove");
        } catch (NetconfException e) {
            Notification.show("Failed to enable GRPC or Telemetry subscription: " + e.getMessage(),
                    Notification.Type.ERROR_MESSAGE);
            e.printStackTrace();
        }

        loadingWindow.close();

        VerticalLayout liveLayout = new VerticalLayout();
        ComboBox<JsonObject> measurements = new ComboBox<>("Measurement");
        TextArea data = new TextArea("Telemetry Data (JSON format)");
        data.setReadOnly(true);
        data.setValue("Waiting for first measurement...");
        data.setSizeFull();

        measurements.setWidth("100%");
        measurements.setEmptySelectionAllowed(false);
        measurements.setTextInputAllowed(false);
        measurements.setItemCaptionGenerator(x -> String.format("[%tT] %s",
                (long)x.getNumber("msg_timestamp"), x.getString("encoding_path")));
        measurements.addValueChangeListener(x -> data.setValue(JsonUtil.stringify(x.getValue(), 2)));

        XRGRPCClient tlsClient = new XRGRPCClient(view.host, grpcPort,
                view.username, view.password, XRGRPCClientSecurity.TLS_UNVERIFIED);
        XRGRPCClient plainClient = new XRGRPCClient(view.host, grpcPort,
                view.username, view.password, XRGRPCClientSecurity.PLAINTEXT);

        LinkedList<JsonObject> telemetryData = new LinkedList<>();
        Consumer<byte[]> telemetryConsumer = x -> view.getUI().access(() -> {
            telemetryData.add(Json.parse(new String(x, StandardCharsets.UTF_8)));
            measurements.setItems(telemetryData);

            if (telemetryData.size() == 1)
                measurements.setValue(telemetryData.get(0));
        });
        Consumer<Throwable> shutdownConsumer = new Consumer<Throwable>() {
			@Override
			public void accept(Throwable t) {
                if (t instanceof XRException) {
                    Notification.show("Subscription error: " + t.getMessage());
                } else if (t != null && t.getCause() instanceof SSLHandshakeException) {
                    plainClient.createSubscription(subscriptionID, SubscriptionEncoding.JSON,
                        telemetryConsumer, this);
                } else if (t != null) {
                    t.printStackTrace();
                }	
			}
        };

        tlsClient.createSubscription(subscriptionID, SubscriptionEncoding.JSON, telemetryConsumer, shutdownConsumer);

        Window liveWindow = new Window("Live Telemetry data: ".concat(groupID));
        liveWindow.setModal(true);
        liveWindow.setResizable(false);
        liveWindow.setDraggable(false);
        liveWindow.setWidth("1000px");
        liveWindow.setHeight("700px");
        liveWindow.addCloseListener(c -> {
            Window unloadingWindow = showLoadingWindow("Deconfiguring Telemetry subscription...");

            try {
                tlsClient.close();
                plainClient.close();
            } catch (Exception e) {
                
            }

            try (NetconfSession session = view.client.createSession()) {
                session.editConfig(Datastore.CANDIDATE, new XMLElement(NS_TELEMETRY, "telemetry-model-driven")
                        .withChild("subscriptions", x -> x.createChild("subscription")
                                .withAttribute(Netconf.NS_NETCONF, "operation", "remove")
                                .withTextContent("subscription-identifier", subscriptionID)));
                session.editConfig(Datastore.CANDIDATE, grpcConfig);
                session.commit();
            } catch (NetconfException e) {
                Notification.show("Failed to enable GRPC or Telemetry subscription: " + e.getMessage(),
                        Notification.Type.ERROR_MESSAGE);
                e.printStackTrace();
            }

            unloadingWindow.close();
        });
        liveLayout.addComponents(measurements, data);
        liveLayout.setExpandRatio(data, 1.0f);
        liveLayout.setSizeFull();
        liveWindow.setContent(liveLayout);
        UI.getCurrent().addWindow(liveWindow);
    }

}
