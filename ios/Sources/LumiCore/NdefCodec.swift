// NDEF codec for the app's own tag payload - a port of the Android app's
// com.lumi.player.nfc.LumiTagNdef.
//
// The per-tag record is a single NDEF record with the custom MIME type
// `application/x-lumi` whose payload is a small JSON object
// (`{"name": ..., "songs": [...]}`).
//
// Reading is lenient: a tag with no lumi record or a malformed payload
// degrades to "no record"; a payload without a song list - the legacy shape
// written before the song list existed - decodes with an empty list; a
// legacy "mode" field - written before the mode left the payload - is
// IGNORED (the play mode is unrelated to tags, so a card with or without
// the field decodes identically: name + songs only).
//
// Writing is strict and never carries the play mode: the written payload is
// name + songs (plain names) only, capped at MAX_PAYLOAD_BYTES.
//
// Per-song download URLs are the card side's "secret option": a song entry
// MAY be a `{"name": ..., "url": ...}` object instead of a plain name. The
// decoder is lenient (a url is kept only when it is a non-blank string) and
// the app NEVER writes a url - its write path cannot propagate or forge
// them.
//
// This file is platform-free (it works on the raw NDEF message bytes, the
// shape CoreNFC hands the app and the shape it accepts for a write), so the
// codec is unit testable off the iOS frameworks.

import Foundation

/// The app's tag payload as decoded from a card.
public struct LumiTagData: Hashable, Sendable {
    public var name: String
    /// The tag's ordered song list by display name (the restore source a
    /// re-registered tag gets its songs back from).
    public var songs: [String]
    /// Optional per-song download URLs keyed by the (clamped) song name -
    /// the card side's secret option, read from the card and never written.
    public var songUrls: [String: String]

    public init(name: String, songs: [String] = [], songUrls: [String: String] = [:]) {
        self.name = name
        self.songs = songs
        self.songUrls = songUrls
    }
}

public enum NdefCodec {
    public static let mimeType = "application/x-lumi"
    public static let maxNameLength = 50
    public static let maxSongNameLength = maxNameLength
    /// Target maximum total NDEF payload size for a write - sized for the
    /// payload WITH the ordered song list (name + song display names):
    /// comfortably above a five-song list on the small NTAG215 (145 user
    /// bytes), far below the large cards.
    public static let maxPayloadBytes = 512

    // MARK: - JSON payload

    /// Serializes [data] to the payload's JSON form. The written payload is
    /// name + plain song names only: no mode field, no url entries. Emits a
    /// canonical two-key object (`{"name":...,"songs":[...]}`) so a written
    /// payload is byte-stable for the size checks.
    public static func toJson(_ data: LumiTagData) -> String {
        let name = jsonStringValue(String(data.name.prefix(maxNameLength)))
        let songsJson = encodeSongNames(data.songs)
        return "{" + "\"name\":" + name + ",\"songs\":" + songsJson + "}"
    }

    /// The songs array as a JSON array of plain strings, in order.
    private static func encodeSongNames(_ songs: [String]) -> String {
        let items = songs.map { jsonStringValue(String($0.prefix(maxSongNameLength))) }
        return "[" + items.joined(separator: ",") + "]"
    }

    /// Encodes [s] as a JSON string literal (quoted, escaped).
    private static func jsonStringValue(_ s: String) -> String {
        var out = "\""
        for scalar in s.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if scalar.value < 0x20 {
                    out += String(format: "\\u%04x", scalar.value)
                } else {
                    out.unicodeScalars.append(scalar)
                }
            }
        }
        out += "\""
        return out
    }

    /// Decodes a payload's JSON. Returns nil when the object is not a lumi
    /// record (malformed, or no "name" field - a record needs a name).
    public static func fromJson(_ json: String) -> LumiTagData? {
        guard
            let data = json.data(using: .utf8),
            let obj = try? JSONSerialization.jsonObject(with: data, options: []),
            let dict = obj as? [String: Any]
        else { return nil }
        guard let rawName = dict["name"] as? String, !rawName.isEmpty else { return nil }
        let (songs, songUrls) = parseSongEntries(dict["songs"] as? [Any])
        return LumiTagData(
            name: String(rawName.prefix(maxNameLength)),
            songs: songs,
            songUrls: songUrls,
        )
    }

    /// The lenient parse of the payload's song entries. A plain-string entry
    /// is the legacy shape (name only) and decodes exactly as before; a
    /// JSON-object entry carries the song's name AND an optional download
    /// url - the url is kept only when it is a non-blank string (a missing
    /// or malformed url means "no url for that song"), and an entry without
    /// a usable name is skipped. Any other entry shape is skipped. Names are
    /// clamped to maxSongNameLength the same way a legacy name is.
    private static func parseSongEntries(_ arr: [Any]?) -> ([String], [String: String]) {
        guard let arr else { return ([], [:]) }
        var names: [String] = []
        var urls: [String: String] = [:]
        for entry in arr {
            let name: String
            var url: String? = nil
            switch entry {
            case let s as String:
                name = String(s.prefix(maxSongNameLength))
            case let o as [String: Any]:
                let raw = (o["name"] as? String) ?? ""
                name = String(raw.prefix(maxSongNameLength))
                if let u = o["url"] as? String,
                    !u.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                {
                    url = u.trimmingCharacters(in: .whitespacesAndNewlines)
                }
            default:
                continue
            }
            if name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { continue }
            names.append(name)
            if let url { urls[name] = url }
        }
        return (names, urls)
    }

    /// The url [urls] (the card payload's clamped-name -> url map) holds for
    /// [songName]: an exact name match first, then - for a stored name longer
    /// than the 50-char clamp - the exact clamped form of it. A short name is
    /// exact-only: it is never a clamp product, so a prefix hit would surface
    /// a different song's url.
    public static func songUrlFor(urls: [String: String], songName: String) -> String? {
        if let u = urls[songName] { return u }
        if songName.count > maxSongNameLength {
            return urls[String(songName.prefix(maxSongNameLength))]
        }
        return nil
    }

    // MARK: - NDEF message bytes

    /// Builds the full NDEF message for [data] as raw bytes: a single
    /// record (TNF byte 4 - Android's Ndef.TNF_MIME_MEDIA, which
    /// NdefRecord.createMime writes for a MIME media type) carrying the
    /// JSON payload. The app layer rebuilds CoreNFC's NDEFMessage from
    /// the record's type + payload before writing (its TNF case may emit
    /// a different byte; the readers - including the Android app - match
    /// on the record's type field, `application/x-lumi`, not the TNF,
    /// so the payloads interoperate).
    ///
    /// The on-tag shape follows the NDEF grammar the Android writer
    /// (NdefWriter + NdefRecord.createMime) emits: the type field is
    /// present ONLY in the short-record form (SNF set, 8-bit payload
    /// length, the 1-byte type length + the type after the length
    /// bytes). The app's payloads fit the short form on every real card
    /// (a NTAG215's user area is 145 bytes, far below 255). For the
    /// 256-512-byte payloads that only a large unknown-capacity card can
    /// take, the record degrades to the long form (24-bit payload length)
    /// WITHOUT the type field - the spec-forbidden alternative (a type in
    /// the long form) would be misparsed by the spec-conformant readers -
    /// and a type-less record matches no reader's type check, exactly the
    /// way the Android app's own such write would behave.
    /// The empty identifier omits the IL bit + its length byte, as
    /// Android's NdefMessageWriter does.
    public static func toNdefMessageBytes(_ data: LumiTagData) -> [UInt8] {
        let type = Array(mimeType.utf8)
        let payload = Array(toJson(data).utf8)
        var record: [UInt8]
        if payload.count <= 255 {
            // The short record (SNF set): the type field is present - the
            // shape the Android writer emits for a record with a type.
            record = [
                0xD4, // MB | ME | SNF | TNF 4 (MIME media type)
                UInt8(payload.count),
                UInt8(type.count),
            ]
            record.append(contentsOf: type)
        } else {
            // The long record: no type field (the grammar's constraint);
            // readers match on the type, so this degrades like the
            // Android app's own such write.
            record = [
                0xC4, // MB | ME | TNF 4 (MIME media type); no SNF, no IL
                UInt8((payload.count >> 16) & 0xFF),
                UInt8((payload.count >> 8) & 0xFF),
                UInt8(payload.count & 0xFF),
            ]
        }
        record.append(contentsOf: payload)
        return record
    }

    /// Parses raw NDEF message bytes (the shape a tag read delivers) and
    /// returns the app's payload when any record carries it; nil otherwise
    /// (a tag with no lumi record or a malformed payload degrades to "no
    /// record" - the lenient read). Handles both the long form (24-bit
    /// payload length) and the short form (SNF set: 8-bit payload length,
    /// the type field follows the length byte), and the optional id-length
    /// byte (IL bit) - the NDEF header grammar, the shape Android's Ndef
    /// parser applies.
    public static func fromNdefMessageBytes(_ bytes: [UInt8]) -> LumiTagData? {
        var offset = 0
        while offset < bytes.count {
            let headerByte = bytes[offset]
            offset += 1
            let snf = headerByte & 0x10 != 0 // short record: 8-bit length, type field present
            let il = headerByte & 0x08 != 0  // id length byte present
            let payloadLength: Int
            if snf {
                guard offset < bytes.count else { break } // malformed: truncated length
                payloadLength = Int(bytes[offset])
                offset += 1
            } else {
                guard offset + 2 < bytes.count else { break } // malformed: truncated length
                payloadLength = Int(bytes[offset]) << 16 | Int(bytes[offset + 1]) << 8 | Int(bytes[offset + 2])
                offset += 3
            }
            // The type field exists only in the short form: its 1-byte
            // length follows the payload-length bytes.
            let typeLength: Int
            if snf {
                guard offset < bytes.count else { break } // malformed: truncated type-length
                typeLength = Int(bytes[offset])
                offset += 1
            } else {
                typeLength = 0
            }
            let idLength: Int
            if il {
                guard offset < bytes.count else { break } // malformed: truncated id-length
                idLength = Int(bytes[offset])
                offset += 1
            } else {
                idLength = 0
            }
            guard offset + typeLength + idLength <= bytes.count else { break } // truncated fields
            let typeStart = offset
            let payloadStart = typeStart + typeLength + idLength
            let payloadEnd = payloadStart + payloadLength
            // The whole record must fit in the buffer; a truncated record is
            // malformed and ends the walk.
            guard payloadEnd <= bytes.count else { break }
            offset = payloadEnd
            let type = Array(bytes[typeStart..<(typeStart + typeLength)])
            let payload = Array(bytes[payloadStart..<payloadEnd])
            if type == Array(mimeType.utf8) {
                if let json = String(data: Data(payload), encoding: .utf8), let data = fromJson(json) {
                    return data
                }
            }
        }
        return nil
    }

    /// Total on-tag size of the message for [data] in bytes.
    public static func messageByteLength(_ data: LumiTagData) -> Int {
        toNdefMessageBytes(data).count
    }
}
