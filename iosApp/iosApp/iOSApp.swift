import SwiftUI
import UIKit
import ValhallaApp

@main
struct ValhallaIOSApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeContainer()
                .ignoresSafeArea(.keyboard)
        }
    }
}

struct ComposeContainer: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
