import Foundation

enum ReminderSound: String, Codable, CaseIterable, Identifiable {
    case system, chime, bell, silent

    var id: String { rawValue }

    var label: String {
        switch self {
        case .system: return "系统默认"
        case .chime: return "轻柔铃声"
        case .bell: return "清脆铃声"
        case .silent: return "静音"
        }
    }
}

struct CheckIn: Codable, Identifiable {
    var id = UUID()
    var title: String
    var hour: Int
    var minute: Int
    var interval: Int
    var sound: ReminderSound
    var completedDay: String? = nil
    var snoozedUntil: Date? = nil

    var timeLabel: String { String(format: "%02d:%02d", hour, minute) }

    var intervalLabel: String {
        interval.isMultiple(of: 60) ? "\(interval / 60) 小时" : "\(interval) 分钟"
    }

    func dailyTime(on day: Date, calendar: Calendar) -> Date {
        calendar.date(bySettingHour: hour, minute: minute, second: 0, of: day) ?? day
    }
}

typealias CheckInHistory = [String: [String: String]]

struct CheckInState: Codable {
    var items: [CheckIn]
    var history: CheckInHistory

    mutating func pruneDeletedHistory() {
        let ids = Set(items.map { $0.id.uuidString })
        history = history.mapValues { records in records.filter { ids.contains($0.key) } }
            .filter { !$0.value.isEmpty }
    }
}

enum CheckInStorage {
    static let group = "group.com.zfz.dbd.Daybyday"

    static func read() -> [CheckIn] { readState().items }

    static func readState() -> CheckInState {
        let defaults = UserDefaults(suiteName: group) ?? .standard
        let decoder = JSONDecoder()
        if let data = defaults.data(forKey: "checkInState"),
           let state = try? decoder.decode(CheckInState.self, from: data) {
            return state
        }

        let items = defaults.data(forKey: "checkIns")
            .flatMap { try? decoder.decode([CheckIn].self, from: $0) } ?? []
        var history: CheckInHistory = [:]
        for item in items {
            if let day = item.completedDay {
                history[day, default: [:]][item.id.uuidString] = item.title
            }
        }
        return CheckInState(items: items, history: history)
    }

    static func write(_ state: CheckInState) {
        let defaults = UserDefaults(suiteName: group) ?? .standard
        if let data = try? JSONEncoder().encode(state) {
            defaults.set(data, forKey: "checkInState")
            defaults.removeObject(forKey: "checkIns")
        }
    }

    static func dayKey(_ date: Date = Date(), calendar: Calendar = .current) -> String {
        let day = calendar.dateComponents([.era, .year, .month, .day], from: date)
        return "\(day.era ?? 0)-\(day.year ?? 0)-\(day.month ?? 0)-\(day.day ?? 0)"
    }
}

struct PlannedReminder {
    let item: CheckIn
    let date: Date
    let day: String
}

enum ReminderPlan {
    static func make(items: [CheckIn], now: Date, calendar: Calendar = .current) -> [PlannedReminder] {
        let today = calendar.startOfDay(for: now)
        let limit = 64
        var daily: [PlannedReminder] = []
        var repeats: [PlannedReminder] = []

        for offset in 0..<7 {
            guard let day = calendar.date(byAdding: .day, value: offset, to: today),
                  let end = calendar.date(byAdding: .day, value: 1, to: day)
            else { continue }
            let key = CheckInStorage.dayKey(day, calendar: calendar)
            for item in items where item.completedDay != key {
                let interval = Double(item.interval * 60)
                let base = item.dailyTime(on: day, calendar: calendar)
                let snoozed = item.snoozedUntil.flatMap { until in
                    calendar.isDate(until.addingTimeInterval(-interval), inSameDayAs: day) ? until : nil
                }
                var next = snoozed ?? base
                if next <= now {
                    let elapsed = now.timeIntervalSince(next)
                    next = next.addingTimeInterval((floor(elapsed / interval) + 1) * interval)
                }
                if next < end {
                    daily.append(PlannedReminder(item: item, date: next, day: key))
                    next = next.addingTimeInterval(interval)
                }
                // A single occurrence cannot use more than the entire pending notification queue.
                for _ in 0..<limit {
                    guard next < end else { break }
                    repeats.append(PlannedReminder(item: item, date: next, day: key))
                    next = next.addingTimeInterval(interval)
                }
            }
        }
        // Reserve part of the queue for daily starts so a short interval cannot crowd them out.
        daily.sort { $0.date < $1.date }
        let reserved = Array(daily.prefix(24))
        let remaining = (Array(daily.dropFirst(reserved.count)) + repeats).sorted { $0.date < $1.date }
        return (reserved + Array(remaining.prefix(limit - reserved.count))).sorted { $0.date < $1.date }
    }
}
