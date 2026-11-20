import SwiftUI

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL

    @State private var connectionStatus: String?
    @State private var testing = false

    var body: some View {
        @Bindable var settings = model

        Form {
            Section {
                Toggle("Use demo data", isOn: $settings.useDemoData)
            } footer: {
                Text("Sample numbers, so you can try everything before your server is running.")
            }

            Section {
                TextField("http://192.168.1.10:8080", text: $settings.serverURL)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .disabled(model.useDemoData)

                Button(action: runConnectionTest) {
                    HStack {
                        Text("Test connection")
                        Spacer()
                        if testing { ProgressView() }
                    }
                }
                .disabled(model.useDemoData || testing)

                if let connectionStatus {
                    Text(connectionStatus)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            } header: {
                Text("Your server")
            } footer: {
                Text("Your Mac or any always-on machine at home. Plain HTTP works on your home network; anything reachable from outside needs HTTPS.")
            }

            Section {
                Button {
                    if let url = URL(string: model.serverURL)?.appendingPathComponent("auth/instagram") {
                        openURL(url)
                    }
                } label: {
                    Label("Connect Instagram account", systemImage: "person.crop.circle.badge.plus")
                }
                .disabled(model.useDemoData || model.api == nil)
            } footer: {
                Text("Needs a Business or Creator account. Switching from a personal account is free in Instagram's settings and takes about a minute.")
            }

            Section("About the data") {
                Text("Every number comes from Instagram's official API, for your own account. Instagram keeps story stats for 24 hours; this app keeps them for good.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("About") {
                LabeledContent("Version",
                               value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "–")
                if let updated = model.lastUpdated {
                    LabeledContent("Last refresh") {
                        Text(updated, style: .relative)
                    }
                }
            }
        }
        .navigationTitle("Settings")
        .onChange(of: model.useDemoData) {
            model.persist()
            Task { await model.refresh() }
        }
        .onChange(of: model.serverURL) {
            model.persist()
        }
    }

    private func runConnectionTest() {
        testing = true
        Task {
            connectionStatus = await model.testConnection()
            testing = false
        }
    }
}
