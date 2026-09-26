// The NDEF tag payload codec's behavior pins - the iOS ports of the
// Android app's LumiTagNdefTest. The JSON shape is byte-compatible with
// the Android app's written payloads, so existing cards work unchanged.

import Foundation
import Testing
@testable import LumiCore

@Test func roundTripNameAndSongs() {
    let data = LumiTagData(
        name: "Hotel",
        songs: ["Morning light", "Night drive", "Static"],
    )
    let json = NdefCodec.toJson(data)
    let decoded = NdefCodec.fromJson(json)
    #expect(decoded != nil)
    #expect(decoded?.name == data.name)
    #expect(decoded?.songs == data.songs)
    #expect(decoded?.songUrls == [:])
}

@Test func writtenPayloadCarriesNoUrlEntries() {
    // The app NEVER writes urls: the written payload carries plain-name
    // entries only (the card side's option cannot be forged or propagated
    // by the app).
    let data = LumiTagData(
        name: "T",
        songs: ["A", "B"],
        songUrls: ["A": "https://example.com/a.mp3"],
    )
    let json = NdefCodec.toJson(data)
    #expect(!json.contains("url"))
    #expect(!json.contains("http"))
    let decoded = NdefCodec.fromJson(json)
    #expect(decoded?.songUrls == [:])
}

@Test func legacyPayloadWithAModeFieldDecodesIdenticallyToWithoutTheField() {
    // Cards written before the mode left the format keep it; the decoder
    // ignores it - with or without the field, the same data decodes.
    let withMode = "{\"name\":\"T\",\"mode\":\"proximity\",\"songs\":[\"A\",\"B\"]}"
    let withoutMode = "{\"name\":\"T\",\"songs\":[\"A\",\"B\"]}"
    let a = NdefCodec.fromJson(withMode)
    let b = NdefCodec.fromJson(withoutMode)
    #expect(a != nil)
    #expect(b != nil)
    #expect(a?.name == b?.name)
    #expect(a?.songs == b?.songs)
}

@Test func legacyPayloadWithoutASongListDecodesWithAnEmptyListNeverNoRecord() {
    let decoded = NdefCodec.fromJson("{\"name\":\"T\"}")
    #expect(decoded?.name == "T")
    #expect(decoded?.songs == [])
}

@Test func aRecordNeedsOnlyANameToDecode() {
    #expect(NdefCodec.fromJson("{}") == nil)
    #expect(NdefCodec.fromJson("garbage") == nil)
    #expect(NdefCodec.fromJson("{\"name\":\"\"}") == nil)
}

@Test func songEntriesAcceptPlainStringsAndObjectsWithANamedUrl() {
    // Lenient read: a plain string entry is the legacy shape (name only);
    // an object entry {name, url} additionally carries the download url.
    let json = "{\"name\":\"T\",\"songs\":[\"A\", {\"name\":\"B\",\"url\":\"https://example.com/b.mp3\"}, {\"name\":\"C\"}, {\"name\":\"D\",\"url\":123}, {\"name\":\"E\",\"url\":\"\"}]}"
    let decoded = NdefCodec.fromJson(json)
    #expect(decoded?.name == "T")
    #expect(decoded?.songs == ["A", "B", "C", "D", "E"])
    #expect(decoded?.songUrls == ["B": "https://example.com/b.mp3"])
}

@Test func nameAndSongNamesClampAtFiftyCharacters() {
    let longName = String(repeating: "x", count: 80)
    let longSong = String(repeating: "y", count: 60)
    let data = LumiTagData(name: longName, songs: [longSong])
    let decoded = NdefCodec.fromJson(NdefCodec.toJson(data))
    #expect(decoded?.name == String(longName.prefix(50)))
    #expect(decoded?.songs == [String(longSong.prefix(50))])
}

@Test func songUrlForResolvesTheExactNameElseTheExactlyClampedForm() {
    // A stored name longer than the clamp resolves through its exact
    // 50-char clamped form (the same resolution as entryByLabel); a short
    // name is exact-only (a prefix hit would resolve a different song).
    let clamped = String(repeating: "s", count: 50)
    let urls = [clamped: "https://example.com/s.mp3"]
    #expect(NdefCodec.songUrlFor(urls: urls, songName: String(repeating: "s", count: 70)) == "https://example.com/s.mp3")
    #expect(NdefCodec.songUrlFor(urls: urls, songName: clamped) == "https://example.com/s.mp3")
    #expect(NdefCodec.songUrlFor(urls: urls, songName: String(repeating: "s", count: 10)) == nil)
}

@Test func messageByteLengthRespectsTheCap() {
    let small = LumiTagData(name: "T", songs: ["A", "B", "C", "D", "E"])
    #expect(NdefCodec.messageByteLength(small) <= NdefCodec.maxPayloadBytes)
    let huge = LumiTagData(
        name: "T",
        songs: (0..<400).map { String(repeating: "song-\($0)-", count: 5) },
    )
    #expect(NdefCodec.messageByteLength(huge) > NdefCodec.maxPayloadBytes)
}

@Test func ndefMessageBytesRoundTrip() {
    let data = LumiTagData(name: "Round trip", songs: ["One", "Two"])
    let bytes = NdefCodec.toNdefMessageBytes(data)
    // The header byte is the Android writer's shape for a record with a
    // type: the short record (SNF set) - MB|ME|SNF|TNF-4 (TNF_MIME_MEDIA),
    // 8-bit payload length, then the type field (the NDEF grammar: the
    // type field exists only in the short form).
    #expect(bytes.first == 0xD4)
    let decoded = NdefCodec.fromNdefMessageBytes(bytes)
    #expect(decoded?.name == data.name)
    #expect(decoded?.songs == data.songs)
    #expect(NdefCodec.messageByteLength(data) == bytes.count)
}

@Test func fromNdefMessageBytesParsesTheShortRecordForm() {
    // The SNF (short-record) form of the same message: an 8-bit payload
    // length with the type field after the length byte (a tag written in
    // that shape must decode identically).
    let type = Array(NdefCodec.mimeType.utf8)
    let payload = Array(Data("{\"name\":\"Short\",\"songs\":[\"A\"]}".utf8))
    var bytes: [UInt8] = [
        0xD4, // MB | ME | SNF | TNF 4
        UInt8(payload.count),
        UInt8(type.count),
    ]
    bytes.append(contentsOf: type)
    bytes.append(contentsOf: payload)
    let decoded = NdefCodec.fromNdefMessageBytes(bytes)
    #expect(decoded?.name == "Short")
    #expect(decoded?.songs == ["A"])
}

@Test func ndefMessageBytesOverTheShortFormCapDegradesToTheTypelessLongForm() {
    // A payload over the short form's 255-byte cap (only a large
    // unknown-capacity card can take one) is written as the long form
    // WITHOUT the type field - the grammar's constraint. A type-less
    // record matches no reader's type check (parity with the Android
    // reader), so the read degrades to "no record" - the same way the
    // Android app's own such write would behave.
    let data = LumiTagData(
        name: "T",
        songs: (0..<60).map { String(repeating: "song-name-\($0) ", count: 5) },
    )
    let json = NdefCodec.toJson(data)
    #expect(json.utf8.count > 255) // the scenario: over the short-form cap
    let bytes = NdefCodec.toNdefMessageBytes(data)
    // The long form: no SNF bit set, 24-bit payload length, no type.
    #expect(bytes.first == 0xC4)
    #expect(NdefCodec.fromNdefMessageBytes(bytes) == nil)
}

@Test func fromNdefMessageBytesRejectsGarbage() {
    #expect(NdefCodec.fromNdefMessageBytes([]) == nil)
    #expect(NdefCodec.fromNdefMessageBytes([1, 2, 3]) == nil)
}
