import AppIntents
import SwiftUI
import WidgetKit

private struct CheckInEntry: TimelineEntry {
    let date: Date
    let items: [CheckIn]
    let size: CheckInWidgetSize
    let page: Int

    var day: String { CheckInStorage.dayKey(date) }
    var completed: Int { items.filter { $0.completedDay == day }.count }
    var pageCount: Int { size.pageCount(for: items.count) }

    var visibleItems: ArraySlice<CheckIn> {
        items.dropFirst(page * size.itemsPerPage).prefix(size.itemsPerPage)
    }
}

private struct CheckInProvider: TimelineProvider {
    func placeholder(in context: Context) -> CheckInEntry {
        CheckInEntry(date: Date(), items: [
            CheckIn(title: "吃药", hour: 8, minute: 0, interval: 30, sound: .system),
            CheckIn(title: "喝水", hour: 9, minute: 0, interval: 30, sound: .system),
            CheckIn(title: "阅读", hour: 20, minute: 0, interval: 30, sound: .system)
        ], size: size(for: context), page: 0)
    }

    func getSnapshot(in context: Context, completion: @escaping (CheckInEntry) -> Void) {
        completion(context.isPreview ? placeholder(in: context) : currentEntry(in: context))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<CheckInEntry>) -> Void) {
        let current = currentEntry(in: context)
        let nextDay = Calendar.current.dateInterval(of: .day, for: current.date)?.end
            ?? current.date.addingTimeInterval(3600)
        let next = CheckInEntry(date: nextDay, items: current.items, size: current.size, page: 0)
        completion(Timeline(entries: [current, next], policy: .atEnd))
    }

    private func currentEntry(in context: Context) -> CheckInEntry {
        let now = Date()
        let items = CheckInStorage.read().sorted { ($0.timeLabel, $0.id.uuidString) < ($1.timeLabel, $1.id.uuidString) }
        let size = size(for: context)
        return CheckInEntry(
            date: now, items: items, size: size,
            page: size.page(for: CheckInStorage.dayKey(now), itemCount: items.count)
        )
    }

    private func size(for context: Context) -> CheckInWidgetSize {
        switch context.family {
        case .systemSmall: return .small
        case .systemLarge: return .large
        default: return .medium
        }
    }
}

private struct CheckInWidgetView: View {
    let entry: CheckInEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text("今日打卡").font(.caption.bold())
                Spacer(minLength: 4)
                Text("\(entry.completed) / \(entry.items.count)").font(.caption.monospacedDigit().bold())
            }
            ProgressView(value: Double(entry.completed), total: Double(max(entry.items.count, 1)))
                .tint(.mint)
                .accessibilityLabel("今日进度")
            if entry.items.isEmpty {
                Text("打开 Daybyday 添加事项").font(.caption).foregroundStyle(.secondary)
                    .padding(.top, 4)
            } else {
                ForEach(entry.visibleItems) { item in
                    row(item)
                }
            }
            Spacer(minLength: 0)
            if entry.pageCount > 1 {
                HStack(spacing: 8) {
                    pageButton("上一页", icon: "chevron.left", page: entry.page - 1)
                    Spacer(minLength: 0)
                    Text("\(entry.page + 1)/\(entry.pageCount)")
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.secondary)
                    Spacer(minLength: 0)
                    pageButton("下一页", icon: "chevron.right", page: entry.page + 1)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .containerBackground(.fill.tertiary, for: .widget)
    }

    private func row(_ item: CheckIn) -> some View {
        HStack(spacing: 4) {
            Text(item.title).font(.caption.weight(.medium)).lineLimit(1)
            Spacer(minLength: 0)
            if item.completedDay == entry.day {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(.secondary)
                    .frame(width: 26, height: 26)
                    .accessibilityLabel("已打卡")
            } else {
                Button(intent: ConfirmWidgetCheckIn(itemID: item.id, day: entry.day)) {
                    if entry.size == .small {
                        Image(systemName: "checkmark").frame(minWidth: 24, minHeight: 24)
                    } else {
                        Text("确认打卡").font(.caption.weight(.semibold))
                    }
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .accessibilityLabel("确认打卡：\(item.title)")
            }
        }
        .frame(minHeight: 26)
    }

    private func pageButton(_ title: String, icon: String, page: Int) -> some View {
        Button(intent: SetCheckInWidgetPage(size: entry.size, page: page, day: entry.day)) {
            Image(systemName: icon).font(.caption2.bold()).frame(minWidth: 24, minHeight: 20)
        }
        .buttonStyle(.plain)
        .disabled(page < 0 || page >= entry.pageCount)
        .opacity(page < 0 || page >= entry.pageCount ? 0.35 : 1)
        .accessibilityLabel(title)
    }
}

@main
struct CheckInWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "CheckInWidget", provider: CheckInProvider()) { entry in
            CheckInWidgetView(entry: entry)
        }
        .configurationDisplayName("今日打卡")
        .description("查看今日进度，并逐项确认打卡。")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}
