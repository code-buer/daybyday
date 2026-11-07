import AppIntents
import Foundation

struct ConfirmWidgetCheckIn: AppIntent {
    static let title: LocalizedStringResource = "确认打卡"
    static let isDiscoverable = false
    static let openAppWhenRun = false

    @Parameter(title: "事项")
    var itemID: String

    @Parameter(title: "日期")
    var day: String

    init() {}

    init(itemID: UUID, day: String) {
        self.itemID = itemID.uuidString
        self.day = day
    }

    func perform() async throws -> some IntentResult {
        guard let id = UUID(uuidString: itemID), day == CheckInStorage.dayKey() else { return .result() }
#if DAYBYDAY_WIDGET_EXTENSION
        throw WidgetActionError.needsApp
#else
        await ReminderCenter.shared.complete(id, day: day)
        return .result()
#endif
    }
}

// The app process owns both the check-in data and the local notification schedule.
@available(iOSApplicationExtension, unavailable)
extension ConfirmWidgetCheckIn: ForegroundContinuableIntent {}

private enum WidgetActionError: LocalizedError {
    case needsApp

    var errorDescription: String? { "请打开 Daybyday 后重试打卡。" }
}

enum CheckInWidgetSize: String {
    case small, medium, large

    var itemsPerPage: Int {
        switch self {
        case .small: return 1
        case .medium: return 2
        case .large: return 5
        }
    }

    private var key: String { "checkInWidgetPage.\(rawValue)" }

    func page(for day: String, itemCount: Int) -> Int {
        let stored = UserDefaults(suiteName: CheckInStorage.group)?.dictionary(forKey: key)
        guard stored?["day"] as? String == day else { return 0 }
        return boundedPage(stored?["page"] as? Int ?? 0, itemCount: itemCount)
    }

    func storePage(_ page: Int, for day: String, itemCount: Int) {
        UserDefaults(suiteName: CheckInStorage.group)?.set(
            ["day": day, "page": boundedPage(page, itemCount: itemCount)], forKey: key
        )
    }

    func pageCount(for itemCount: Int) -> Int {
        max(1, (itemCount + itemsPerPage - 1) / itemsPerPage)
    }

    private func boundedPage(_ page: Int, itemCount: Int) -> Int {
        min(max(0, page), pageCount(for: itemCount) - 1)
    }
}

struct SetCheckInWidgetPage: AppIntent {
    static let title: LocalizedStringResource = "切换打卡事项页"
    static let isDiscoverable = false

    @Parameter(title: "小部件大小")
    var size: String

    @Parameter(title: "页码")
    var page: Int

    @Parameter(title: "日期")
    var day: String

    init() {}

    init(size: CheckInWidgetSize, page: Int, day: String) {
        self.size = size.rawValue
        self.page = page
        self.day = day
    }

    func perform() async throws -> some IntentResult {
        if day == CheckInStorage.dayKey(), let size = CheckInWidgetSize(rawValue: size) {
            size.storePage(page, for: day, itemCount: CheckInStorage.read().count)
        }
        return .result()
    }
}
