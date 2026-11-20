import SwiftUI

@main
struct InstAnalyticsApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .tint(Palette.accent)
        }
    }
}

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        TabView {
            NavigationStack { AccountView() }
                .tabItem { Label("Account", systemImage: "person.crop.circle") }

            NavigationStack { StoriesView() }
                .tabItem { Label("Stories", systemImage: "circle.dashed.inset.filled") }

            NavigationStack { PostsView() }
                .tabItem { Label("Posts", systemImage: "square.grid.2x2") }

            NavigationStack { RetentionView() }
                .tabItem { Label("Retention", systemImage: "chart.line.downtrend.xyaxis") }

            NavigationStack { SettingsView() }
                .tabItem { Label("Settings", systemImage: "gearshape") }
        }
        .task { await model.refresh() }
    }
}
