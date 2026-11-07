import Foundation

/// The interface of the Rust core this code was written for. The core refuses to run when its own version
/// differs (a library and its bindings from different builds would corrupt memory, not just misbehave).
public let expectedCoreApiVersion: UInt32 = 2

/// Entry point for checks that every client does at start-up.
public enum CoreRuntime {
    /// Checks that the loaded core speaks the interface this app expects and returns its description.
    ///
    /// - Throws: ``CoreError`` when the versions differ.
    public static func verify() throws -> CoreInfo {
        try requireApiVersion(expected: expectedCoreApiVersion)
        return coreInfo()
    }
}
