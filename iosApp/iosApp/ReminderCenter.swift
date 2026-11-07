import Combine
import Foundation
import UIKit
import UserNotifications
import WidgetKit

@MainActor
final class ReminderCenter: ObservableObject {
    static let shared = ReminderCenter()
    static let category = "CHECK_IN"
    static let done = "DONE"
    static let snooze = "SNOOZE"

    @Published private(set) var items: [CheckIn]
    @Published private(set) var history: CheckInHistory
    @Published private(set) var today = CheckInStorage.dayKey()
    @Published private(set) var authorization: UNAuthorizationStatus = .notDetermined
    @Published private(set) var scheduledThrough: Date?
    @Published private(set) var scheduleFailed = false

    private let center = UNUserNotificationCenter.current()
    private var scheduleTask: Task<Void, Never>?
    private var needsSchedule = false

    private init() {
        var state = CheckInStorage.readState()
        state.pruneDeletedHistory()
        items = state.items
        history = state.history
        CheckInStorage.write(state)
    }

    func register() {
        let actions = [
            UNNotificationAction(identifier: Self.done, title: "确认打卡"),
            UNNotificationAction(identifier: Self.snooze, title: "稍等一下")
        ]
        center.setNotificationCategories([
            UNNotificationCategory(identifier: Self.category, actions: actions, intentIdentifiers: [])
        ])
    }

    func activate() async {
        today = CheckInStorage.dayKey()
        await reschedule()
    }

    func requestNotifications() async {
        if authorization == .denied {
            if let url = URL(string: UIApplication.openSettingsURLString) {
                await UIApplication.shared.open(url)
            }
            return
        }
        _ = try? await center.requestAuthorization(options: [.alert, .sound, .badge])
        await activate()
    }

    func save(_ item: CheckIn) async {
        var updated = item
        updated.completedDay = items.first(where: { $0.id == item.id })?.completedDay
        updated.snoozedUntil = nil
        items.removeAll { $0.id == item.id }
        items.append(updated)
        persist()
        await clearDelivered(for: item.id)
        if authorization == .notDetermined {
            await requestNotifications()
        } else {
            await reschedule()
        }
    }

    func delete(_ id: UUID) async {
        items.removeAll { $0.id == id }
        var state = CheckInState(items: items, history: history)
        state.pruneDeletedHistory()
        history = state.history
        persist()
        await reschedule()
        await clearDelivered(for: id)
    }

    func complete(_ id: UUID, day: String) async {
        guard day == CheckInStorage.dayKey(),
              let index = items.firstIndex(where: { $0.id == id }),
              items[index].completedDay != day else { return }
        if history[day]?[id.uuidString] == nil {
            history[day, default: [:]][id.uuidString] = items[index].title
        }
        items[index].completedDay = day
        items[index].snoozedUntil = nil
        persist()
        await clearDelivered(for: id)
        await reschedule()
    }

    func handle(_ response: UNNotificationResponse) async {
        let info = response.notification.request.content.userInfo
        guard let value = info["id"] as? String, let id = UUID(uuidString: value),
              let day = info["day"] as? String, day == CheckInStorage.dayKey()
        else { return }
        switch response.actionIdentifier {
        case Self.done:
            await complete(id, day: day)
        case Self.snooze:
            guard let index = items.firstIndex(where: { $0.id == id }),
                  items[index].completedDay != day,
                  (items[index].snoozedUntil ?? .distantPast) <= response.notification.date
            else { return }
            items[index].snoozedUntil = Date().addingTimeInterval(Double(items[index].interval * 60))
            persist()
            await clearDelivered(for: id)
            await reschedule()
        default:
            await activate()
        }
    }

    private func persist() {
        today = CheckInStorage.dayKey()
        CheckInStorage.write(CheckInState(items: items, history: history))
        WidgetCenter.shared.reloadAllTimelines()
    }

    private func clearDelivered(for id: UUID) async {
        let delivered = await center.deliveredNotifications()
        let matching = delivered.filter { $0.request.content.userInfo["id"] as? String == id.uuidString }
        center.removeDeliveredNotifications(withIdentifiers: matching.map { $0.request.identifier })
    }

    private func reschedule() async {
        needsSchedule = true
        if let scheduleTask {
            await scheduleTask.value
            return
        }
        let task = Task {
            while needsSchedule {
                needsSchedule = false
                await installPlan()
            }
            scheduleTask = nil
        }
        scheduleTask = task
        await task.value
    }

    private func installPlan() async {
        authorization = await center.notificationSettings().authorizationStatus
        let plan = ReminderPlan.make(items: items, now: Date())
        let pending = await center.pendingNotificationRequests()
        let existing = Set(pending.map(\.identifier))
        let desired = Set(plan.map(identifier))
        center.removePendingNotificationRequests(withIdentifiers: Array(existing.subtracting(desired)))
        scheduledThrough = nil
        scheduleFailed = false

        guard authorization == .authorized || authorization == .provisional || authorization == .ephemeral else {
            return
        }
        for reminder in plan {
            let key = identifier(reminder)
            if !existing.contains(key) {
                let content = UNMutableNotificationContent()
                content.title = reminder.item.title
                content.body = "该打卡了 · 未打卡时每 \(reminder.item.intervalLabel)提醒"
                content.categoryIdentifier = Self.category
                content.threadIdentifier = reminder.item.id.uuidString
                content.userInfo = ["id": reminder.item.id.uuidString, "day": reminder.day]
                switch reminder.item.sound {
                case .system: content.sound = .default
                case .silent: break
                case .chime, .bell:
                    content.sound = UNNotificationSound(named: .init(reminder.item.sound.rawValue + ".aiff"))
                }
                let delay = reminder.date.timeIntervalSinceNow
                guard delay > 0 else { continue }
                let trigger = UNTimeIntervalNotificationTrigger(timeInterval: delay, repeats: false)
                do {
                    try await center.add(UNNotificationRequest(identifier: key, content: content, trigger: trigger))
                } catch {
                    scheduleFailed = true
                    continue
                }
            }
            scheduledThrough = reminder.date
        }
    }

    private func identifier(_ reminder: PlannedReminder) -> String {
        let item = reminder.item
        return "\(item.id)-\(Int(reminder.date.timeIntervalSince1970))-\(item.interval)-\(item.sound.rawValue)-\(item.title)"
    }
}
