// swift-tools-version: 6.0
import PackageDescription

// The runtime around the Rust core: the clock, the secrets, the storage layout, the actor that drives a node and
// the transport seam (the Swift counterpart of the Android module `core:runtime`).
let package = Package(
    name: "CrisisRuntime",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "CrisisRuntime", targets: ["CrisisRuntime"]),
    ],
    dependencies: [
        .package(path: "../CrisisCore"),
    ],
    targets: [
        .target(
            name: "CrisisRuntime",
            dependencies: [.product(name: "CrisisCore", package: "CrisisCore")],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .testTarget(
            name: "CrisisRuntimeTests",
            dependencies: [
                "CrisisRuntime",
                .product(name: "CrisisCore", package: "CrisisCore"),
            ],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
    ]
)
