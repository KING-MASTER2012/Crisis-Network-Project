// swift-tools-version: 6.0
import PackageDescription

// The Swift face of the Rust core.
//
// CrisisCoreFFI.xcframework and Sources/CrisisCore/Generated/ do not exist in a fresh checkout: they are
// produced by `scripts/build-apple.sh` (see clients/apple/README.md).
let package = Package(
    name: "CrisisCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "CrisisCore", targets: ["CrisisCore"]),
    ],
    targets: [
        // The Rust static libraries (iOS device, iOS simulator, macOS) with the C header of the bindings.
        .binaryTarget(name: "CrisisCoreFFI", path: "CrisisCoreFFI.xcframework"),
        .target(
            name: "CrisisCore",
            dependencies: ["CrisisCoreFFI"],
            // The generated bindings are Swift 5 code; the hand-written files of this target are fine with it too.
            swiftSettings: [.swiftLanguageMode(.v5)],
            linkerSettings: [
                // What a Rust static library needs from the system.
                .linkedLibrary("c++"),
                .linkedLibrary("resolv"),
                .linkedLibrary("iconv"),
                .linkedFramework("Security"),
                .linkedFramework("CoreFoundation"),
            ]
        ),
        .testTarget(
            name: "CrisisCoreTests",
            dependencies: ["CrisisCore"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
    ]
)
