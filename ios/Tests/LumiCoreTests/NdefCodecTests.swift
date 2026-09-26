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
    // The header byte is the Android writer's shape: the short record
    // (SNF set) - MB|ME|SNF|TNF-4 (TNF_MIME_MEDIA) - the 1-byte type
    // length first, then the 8-bit payload length (the AOSP writer's
    // field order).
    #expect(bytes.first == 0xD4)
    #expect(bytes[1] == Array(NdefCodec.mimeType.utf8).count)
    #expect(bytes[2] == NdefCodec.toJson(data).utf8.count)
    let decoded = NdefCodec.fromNdefMessageBytes(bytes)
    #expect(decoded?.name == data.name)
    #expect(decoded?.songs == data.songs)
    #expect(NdefCodec.messageByteLength(data) == bytes.count)
}

@Test func ndefMessageBytesMatchTheAOSPShortFormByteForByte() {
    // The golden on-tag shape: a fixed input must encode to the exact
    // byte sequence Android's NdefRecord writer emits - header, type
    // length, payload length, the type field, the JSON payload.
    let type = Array(NdefCodec.mimeType.utf8)
    let json = "{\"name\":\"Beach\",\"songs\":[]}"
    let payload = Array(json.utf8)
    var expected: [UInt8] = [0xD4, UInt8(type.count), UInt8(payload.count)]
    expected.append(contentsOf: type)
    expected.append(contentsOf: payload)
    #expect(NdefCodec.toNdefMessageBytes(LumiTagData(name: "Beach")) == expected)
}

@Test func fromNdefMessageBytesParsesTheAOSPShortRecordForm() {
    // Bytes in the AOSP short-record order (type length before the
    // 8-bit payload length) - the shape the Android app writes - must
    // decode to the payload.
    let type = Array(NdefCodec.mimeType.utf8)
    let payload = Array(Data("{\"name\":\"Short\",\"songs\":[\"A\"]}".utf8))
    var bytes: [UInt8] = [
        0xD4, // MB | ME | SNF | TNF 4
        UInt8(type.count),
        UInt8(payload.count),
    ]
    bytes.append(contentsOf: type)
    bytes.append(contentsOf: payload)
    let decoded = NdefCodec.fromNdefMessageBytes(bytes)
    #expect(decoded?.name == "Short")
    #expect(decoded?.songs == ["A"])
}

@Test func ndefMessageBytesAtTheShortFormCapUseTheLongForm() {
    // A payload of 256 bytes or more (only a large card can take one)
    // is written as the long form: 32-bit payload length and the type
    // field still present (the AOSP writer's long-form shape) - and it
    // reads back through the same grammar. The name is clamped to 50
    // chars, so the size comes from the song list (eight 30-char names
    // put the JSON over the 256-byte short-form cap).
    let data = LumiTagData(
        name: "L",
        songs: Array(repeating: String(repeating: "s", count: 30), count: 8),
    )
    let json = NdefCodec.toJson(data)
    #expect(json.utf8.count >= 256) // the scenario: over the short-form cap
    let type = Array(NdefCodec.mimeType.utf8)
    let len = json.utf8.count
    var expected: [UInt8] = [
        0xC4, // MB | ME | TNF 4 (no SNF, no IL)
        UInt8(type.count),
        UInt8((len >> 24) & 0xFF),
        UInt8((len >> 16) & 0xFF),
        UInt8((len >> 8) & 0xFF),
        UInt8(len & 0xFF),
    ]
    expected.append(contentsOf: type)
    expected.append(contentsOf: Array(json.utf8))
    #expect(NdefCodec.toNdefMessageBytes(data) == expected)
    #expect(NdefCodec.fromNdefMessageBytes(expected)?.name == data.name)
}

@Test func fromNdefMessageBytesWalksToTheLumiRecordAmongOtherRecords() {
    // A card carrying other NDEF records before the app's record (the
    // AOSP multi-record shape: the first record's ME bit is clear, the
    // second's MB bit is clear): the walk skips to the lumi record.
    let type = Array(NdefCodec.mimeType.utf8)
    let payload = Array(Data("{\"name\":\"Walk\",\"songs\":[\"A\", \"B\"]}".utf8))
    var bytes: [UInt8] = []
    // A foreign well-known text record: MB set, ME clear, SNF set,
    // TNF 1, type "T", payload "x".
    bytes.append(contentsOf: [0x80 | 0x10 | 0x01, 1, 1, 0x54, 0x78])
    // The app's record: MB clear, ME set, SNF set, TNF 4.
    bytes.append(contentsOf: [0x40 | 0x10 | 0x04, UInt8(type.count), UInt8(payload.count)])
    bytes.append(contentsOf: type)
    bytes.append(contentsOf: payload)
    let decoded = NdefCodec.fromNdefMessageBytes(bytes)
    #expect(decoded?.name == "Walk")
    #expect(decoded?.songs == ["A", "B"])
}

@Test func fromNdefMessageBytesRejectsGarbage() {
    #expect(NdefCodec.fromNdefMessageBytes([]) == nil)
    #expect(NdefCodec.fromNdefMessageBytes([1, 2, 3]) == nil)
}
