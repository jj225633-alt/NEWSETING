import * as React from "react";
import { Link } from "react-router";
import { toast } from "sonner";
import { ArrowLeft, RefreshCw } from "lucide-react";

import { Button } from "~/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "~/components/ui/card";
import { Input } from "~/components/ui/input";
import { Progress } from "~/components/ui/progress";
import { Switch } from "~/components/ui/switch";
import { Textarea } from "~/components/ui/textarea";
import { resolveFileUrl } from "~/lib/files";
import { cn } from "~/lib/utils";
import api from "~/services/api";

// ───────── 和 app/web/routes/HoshioRoutes.kt 对齐的型别 ─────────

interface WakeRecord {
  id: string;
  at: number;
  trigger: string;
  action: string;
  note: string;
}

interface MoodCheckin {
  date: string;
  emoji: string;
  label: string;
  note: string;
  at: number;
}

interface DailyQuestion {
  id: string;
  date: string;
  asker: string;
  question: string;
  herAnswer: string;
  hisAnswer: string;
}

interface Overview {
  assistantName: string;
  state: { mood: string; energy: number; reason: string; updatedAt: number };
  wakeLog: WakeRecord[];
  unreadLetters: number;
  todayMood: MoodCheckin | null;
  moodOptions: string[][];
  question: DailyQuestion | null;
  pet: {
    name: string;
    species: string;
    status: string;
    fullness: number;
    happiness: number;
    cleanliness: number;
    ageDays: number;
  } | null;
  routines: { id: string; emoji: string; label: string; times: string[]; doneToday: number[] }[];
  openPromises: number;
}

interface Letter {
  id: string;
  title: string;
  content: string;
  at: number;
  read: boolean;
}

interface DiaryEntry {
  id: string;
  date: string;
  author: string;
  content: string;
  createdAt: number;
  visibleToAi: boolean;
}

interface OurPromise {
  id: string;
  text: string;
  by: string;
  due: string | null;
  done: boolean;
  doneAt: number | null;
}

interface CapsuleDto {
  id: string;
  by: string;
  title: string;
  openOn: string;
  unlocked: boolean;
  opened: boolean;
  content: string | null;
}

interface AlbumPhoto {
  id: string;
  path: string;
  herCaption: string;
  hisCaption: string;
  at: number;
}

type Tab = "overview" | "letters" | "diary" | "promises" | "capsules" | "album";

const TABS: { value: Tab; label: string }[] = [
  { value: "overview", label: "今天" },
  { value: "letters", label: "信箱" },
  { value: "diary", label: "日記與心情" },
  { value: "promises", label: "我們的約定" },
  { value: "capsules", label: "時光膠囊" },
  { value: "album", label: "回憶相簿" },
];

function fmt(ms: number): string {
  const d = new Date(ms);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${pad(d.getMonth() + 1)}/${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function today(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function errorText(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

/** 读一个 API，附重新整理 */
function useApi<T>(url: string) {
  const [data, setData] = React.useState<T | null>(null);
  const [error, setError] = React.useState<string | null>(null);
  const reload = React.useCallback(async () => {
    try {
      setData(await api.get<T>(url));
      setError(null);
    } catch (e) {
      setError(errorText(e));
    }
  }, [url]);
  React.useEffect(() => {
    void reload();
  }, [reload]);
  return { data, error, reload };
}

async function act(fn: () => Promise<unknown>, success?: string, after?: () => unknown) {
  try {
    await fn();
    if (success) toast.success(success);
    await after?.();
  } catch (e) {
    toast.error(errorText(e));
  }
}

function Section({ title, children, className }: { title: string; children: React.ReactNode; className?: string }) {
  return (
    <Card className={cn("gap-3", className)}>
      <CardHeader>
        <CardTitle className="text-base">{title}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">{children}</CardContent>
    </Card>
  );
}

function Empty({ children }: { children: React.ReactNode }) {
  return <p className="text-sm text-muted-foreground">{children}</p>;
}

// ───────── 今天 ─────────

function OverviewTab() {
  const { data, error, reload } = useApi<Overview>("hoshio/overview");
  const [note, setNote] = React.useState("");
  const [tell, setTell] = React.useState(true);
  const [answer, setAnswer] = React.useState("");
  const [ask, setAsk] = React.useState("");

  if (error) return <Empty>讀取失敗：{error}</Empty>;
  if (!data) return <Empty>讀取中…</Empty>;
  const name = data.assistantName;

  return (
    <div className="grid gap-4 md:grid-cols-2">
      <Section title={`${name} 現在`} className="bg-primary/5">
        <div className="text-2xl font-semibold">{data.state.mood}</div>
        {data.state.reason && <p className="text-sm">因為 {data.state.reason}</p>}
        <div className="flex items-center gap-3 text-sm">
          <span>精神 {data.state.energy}/100</span>
          <Progress value={data.state.energy} className="flex-1" />
        </div>
        {data.unreadLetters > 0 && <p className="text-sm font-medium text-primary">💌 有 {data.unreadLetters} 封未讀的信</p>}
      </Section>

      <Section title="今天的心情">
        {data.todayMood && (
          <p className="text-lg">
            {data.todayMood.emoji} {data.todayMood.label}
            {data.todayMood.note && <span className="text-sm text-muted-foreground">・{data.todayMood.note}</span>}
          </p>
        )}
        <div className="flex flex-wrap gap-2">
          {data.moodOptions.map(([emoji, label]) => (
            <Button
              key={label}
              size="sm"
              variant={data.todayMood?.label === label ? "default" : "outline"}
              onClick={() =>
                act(
                  () => api.post("hoshio/mood", { emoji, label, note, tell }),
                  tell ? `打卡了，${name} 會在聊天裡回你` : "打卡了",
                  reload,
                )
              }
            >
              {emoji} {label}
            </Button>
          ))}
        </div>
        <Input placeholder="想說點什麼（可不填，先寫再點心情）" value={note} onChange={(e) => setNote(e.target.value)} />
        <label className="flex items-center gap-2 text-sm">
          <Switch checked={tell} onCheckedChange={setTell} /> 讓他知道
        </label>
      </Section>

      <Section title="每日一問">
        {data.question ? (
          <>
            <p className="text-lg font-medium">{data.question.question}</p>
            <p className="text-xs text-muted-foreground">{data.question.asker === "him" ? "他問的" : "你問的"}</p>
            {data.question.herAnswer ? (
              <div className="space-y-1 text-sm">
                <p>你：{data.question.herAnswer}</p>
                <p>他：{data.question.hisAnswer || "（還沒回答）"}</p>
              </div>
            ) : (
              <div className="flex gap-2">
                <Input placeholder="你的回答" value={answer} onChange={(e) => setAnswer(e.target.value)} />
                <Button
                  disabled={!answer.trim()}
                  onClick={() =>
                    act(() => api.post("hoshio/question/answer", { text: answer }), "回答了，他會在聊天裡回應", () => {
                      setAnswer("");
                      return reload();
                    })
                  }
                >
                  回答
                </Button>
              </div>
            )}
          </>
        ) : (
          <div className="flex gap-2">
            <Input placeholder="今天還沒有問題，問他一題吧" value={ask} onChange={(e) => setAsk(e.target.value)} />
            <Button
              disabled={!ask.trim()}
              onClick={() =>
                act(() => api.post("hoshio/question/ask", { text: ask }), "問了，他會在聊天裡回答", () => {
                  setAsk("");
                  return reload();
                })
              }
            >
              問他
            </Button>
          </div>
        )}
      </Section>

      <Section title="生活照顧">
        {data.routines.length === 0 && <Empty>還沒有提醒。可以跟他說「每天三點提醒我喝水」。</Empty>}
        {data.routines.map((r) => (
          <div key={r.id} className="flex items-center gap-3">
            <div className="flex-1">
              <div className="font-medium">
                {r.emoji} {r.label}
              </div>
              <div className="text-xs text-muted-foreground">
                {r.times.join("、")}・今天
                {r.doneToday.length ? r.doneToday.map((t) => fmt(t).slice(6)).join("、") : "還沒做"}
              </div>
            </div>
            <Button size="sm" variant="secondary" onClick={() => act(() => api.post(`hoshio/routines/${r.id}/done`), "記下來了 ✓", reload)}>
              做了
            </Button>
          </div>
        ))}
      </Section>

      {data.pet && (
        <Section title={`${data.pet.species} ${data.pet.name}`}>
          <p className="text-sm">
            來家裡第 {data.pet.ageDays + 1} 天・{data.pet.status}
          </p>
          {(
            [
              ["飽足", data.pet.fullness],
              ["心情", data.pet.happiness],
              ["清潔", data.pet.cleanliness],
            ] as const
          ).map(([label, value]) => (
            <div key={label} className="flex items-center gap-3 text-sm">
              <span className="w-8">{label}</span>
              <Progress value={value} className="flex-1" />
              <span className="w-8 text-right">{value}</span>
            </div>
          ))}
          <div className="flex flex-wrap gap-2">
            {(
              [
                ["feed", "🍖 餵食"],
                ["play", "🧶 玩耍"],
                ["clean", "🛁 洗澡"],
                ["pet", "🤲 摸摸"],
              ] as const
            ).map(([action, label]) => (
              <Button key={action} size="sm" variant="outline" onClick={() => act(() => api.post(`hoshio/pet/${action}`), undefined, reload)}>
                {label}
              </Button>
            ))}
          </div>
        </Section>
      )}

      <Section title="他的醒來紀錄" className="md:col-span-2">
        {data.wakeLog.length === 0 && <Empty>還沒有紀錄。</Empty>}
        {data.wakeLog.map((w) => (
          <div key={w.id} className="text-sm">
            <span className="text-muted-foreground">{fmt(w.at)}</span>{" "}
            {w.action === "talked" ? "💬" : w.action === "silent" ? "😴" : w.action === "tools" ? "🛠️" : "⚠️"} {w.trigger}
            {w.note && <span className="text-muted-foreground">・{w.note}</span>}
          </div>
        ))}
      </Section>
    </div>
  );
}

// ───────── 信箱 ─────────

function LettersTab() {
  const { data, error, reload } = useApi<Letter[]>("hoshio/letters");
  const [open, setOpen] = React.useState<string | null>(null);
  if (error) return <Empty>讀取失敗：{error}</Empty>;
  if (!data) return <Empty>讀取中…</Empty>;
  if (data.length === 0) return <Empty>信箱還是空的。他想寫信給你的時候，信會寄到這裡 💌</Empty>;
  return (
    <div className="space-y-3">
      {data.map((l) => (
        <Card
          key={l.id}
          className={cn("cursor-pointer gap-2 transition-colors", !l.read && "border-primary/50 bg-primary/5")}
          onClick={() => {
            setOpen(open === l.id ? null : l.id);
            if (!l.read) void act(() => api.post(`hoshio/letters/${l.id}/read`), undefined, reload);
          }}
        >
          <CardHeader>
            <CardTitle className="text-base">
              {l.read ? "✉️" : "💌"} {l.title}
            </CardTitle>
            <p className="text-xs text-muted-foreground">
              {fmt(l.at)}
              {!l.read && "・未讀"}
            </p>
          </CardHeader>
          {open === l.id && <CardContent className="whitespace-pre-wrap leading-relaxed">{l.content}</CardContent>}
        </Card>
      ))}
    </div>
  );
}

// ───────── 日记与心情 ─────────

function DiaryTab() {
  const { data, error, reload } = useApi<{ entries: DiaryEntry[]; moods: MoodCheckin[] }>("hoshio/diary");
  const [date, setDate] = React.useState(today());
  const [content, setContent] = React.useState("");
  const [visible, setVisible] = React.useState(true);
  if (error) return <Empty>讀取失敗：{error}</Empty>;
  if (!data) return <Empty>讀取中…</Empty>;
  const moods = new Map(data.moods.map((m) => [m.date, m]));
  const dates = Array.from(new Set([...data.entries.map((e) => e.date), ...data.moods.map((m) => m.date)])).sort().reverse();

  return (
    <div className="space-y-4">
      <Section title="寫日記">
        <div className="flex flex-wrap items-center gap-3">
          <Input type="date" value={date} onChange={(e) => setDate(e.target.value)} className="w-auto" />
          <label className="flex items-center gap-2 text-sm">
            <Switch checked={visible} onCheckedChange={setVisible} /> 讓他看得到
          </label>
        </div>
        <Textarea rows={5} placeholder="今天發生了什麼…" value={content} onChange={(e) => setContent(e.target.value)} />
        <Button
          disabled={!content.trim()}
          onClick={() =>
            act(() => api.post("hoshio/diary", { date, content, visibleToAi: visible }), "寫好了", () => {
              setContent("");
              return reload();
            })
          }
        >
          儲存
        </Button>
      </Section>
      {dates.length === 0 && <Empty>還沒有日記。</Empty>}
      {dates.map((d) => {
        const mood = moods.get(d);
        const entries = data.entries.filter((e) => e.date === d);
        return (
          <Section key={d} title={`${d}${mood ? `　${mood.emoji} ${mood.label}` : ""}`}>
            {mood?.note && <p className="text-sm text-muted-foreground">心情：{mood.note}</p>}
            {entries.map((e) => (
              <div key={e.id} className="space-y-1">
                <p className="text-xs font-medium text-primary">
                  {e.author === "ai" ? "他寫的" : `我寫的${e.visibleToAi ? "" : "（只有我看得到）"}`}
                </p>
                <p className="whitespace-pre-wrap text-sm leading-relaxed">{e.content}</p>
              </div>
            ))}
          </Section>
        );
      })}
    </div>
  );
}

// ───────── 我们的约定 ─────────

function PromisesTab() {
  const { data, error, reload } = useApi<OurPromise[]>("hoshio/promises");
  const [text, setText] = React.useState("");
  const [due, setDue] = React.useState("");
  const [by, setBy] = React.useState("both");
  if (error) return <Empty>讀取失敗：{error}</Empty>;
  if (!data) return <Empty>讀取中…</Empty>;
  const byLabel = (b: string) => (b === "her" ? "你答應的" : b === "him" ? "他答應的" : "一起的");
  return (
    <div className="space-y-4">
      <Section title="新增約定">
        <Input placeholder="例如：下次一起去看海" value={text} onChange={(e) => setText(e.target.value)} />
        <div className="flex flex-wrap items-center gap-2">
          {(
            [
              ["her", "我答應的"],
              ["him", "他答應的"],
              ["both", "一起"],
            ] as const
          ).map(([v, l]) => (
            <Button key={v} size="sm" variant={by === v ? "default" : "outline"} onClick={() => setBy(v)}>
              {l}
            </Button>
          ))}
          <Input type="date" value={due} onChange={(e) => setDue(e.target.value)} className="w-auto" />
          <Button
            disabled={!text.trim()}
            onClick={() =>
              act(() => api.post("hoshio/promises", { text, by, due: due || null }), "約好了", () => {
                setText("");
                setDue("");
                return reload();
              })
            }
          >
            約好了
          </Button>
        </div>
      </Section>
      {data.length === 0 && <Empty>還沒有約定。聊天時說到「下次一起…」他也會自己記下來。</Empty>}
      {data.map((p) => (
        <Card key={p.id} className={cn("flex-row items-center gap-3 px-4 py-3", p.done && "opacity-70")}>
          <input
            type="checkbox"
            checked={p.done}
            className="size-4 accent-[var(--primary)]"
            onChange={() => act(() => api.post(`hoshio/promises/${p.id}/toggle`), undefined, reload)}
          />
          <div className="flex-1">
            <div className={cn(p.done && "line-through")}>{p.text}</div>
            <div className="text-xs text-muted-foreground">
              {byLabel(p.by)}
              {p.due && `・${p.due}`}
              {p.doneAt && `・${fmt(p.doneAt)} 完成`}
            </div>
          </div>
        </Card>
      ))}
    </div>
  );
}

// ───────── 时光胶囊 ─────────

function CapsulesTab() {
  const { data, error, reload } = useApi<CapsuleDto[]>("hoshio/capsules");
  const [title, setTitle] = React.useState("");
  const [content, setContent] = React.useState("");
  const [openOn, setOpenOn] = React.useState("");
  const [reading, setReading] = React.useState<string | null>(null);
  if (error) return <Empty>讀取失敗：{error}</Empty>;
  if (!data) return <Empty>讀取中…</Empty>;
  return (
    <div className="space-y-4">
      <Section title="寫一顆時光膠囊">
        <Input placeholder="標題" value={title} onChange={(e) => setTitle(e.target.value)} />
        <Textarea rows={4} placeholder="寫給未來…" value={content} onChange={(e) => setContent(e.target.value)} />
        <div className="flex items-center gap-2">
          <span className="text-sm">哪天打開</span>
          <Input type="date" value={openOn} onChange={(e) => setOpenOn(e.target.value)} className="w-auto" />
          <Button
            disabled={!content.trim() || !openOn}
            onClick={() =>
              act(() => api.post("hoshio/capsules", { title, content, openOn }), "封起來了", () => {
                setTitle("");
                setContent("");
                setOpenOn("");
                return reload();
              })
            }
          >
            封起來
          </Button>
        </div>
      </Section>
      {data.map((c) => (
        <Card
          key={c.id}
          className={cn("gap-2", c.unlocked && "cursor-pointer", c.unlocked && !c.opened && "border-primary/50 bg-primary/5")}
          onClick={() => {
            if (!c.unlocked) return;
            setReading(reading === c.id ? null : c.id);
            if (!c.opened) void act(() => api.post(`hoshio/capsules/${c.id}/open`), undefined, reload);
          }}
        >
          <CardHeader>
            <CardTitle className="text-base">
              {!c.unlocked ? "🔒" : c.opened ? "📖" : "🎁"} {c.title}
            </CardTitle>
            <p className="text-xs text-muted-foreground">
              {c.by === "him" ? "他寫的" : "你寫的"}・{c.unlocked ? (c.opened ? "已打開" : "可以打開了！") : `${c.openOn} 才能打開`}
            </p>
          </CardHeader>
          {reading === c.id && c.content && <CardContent className="whitespace-pre-wrap leading-relaxed">{c.content}</CardContent>}
        </Card>
      ))}
    </div>
  );
}

// ───────── 回忆相簿 ─────────

function AlbumTab() {
  const { data, error } = useApi<AlbumPhoto[]>("hoshio/album");
  if (error) return <Empty>讀取失敗：{error}</Empty>;
  if (!data) return <Empty>讀取中…</Empty>;
  if (data.length === 0) return <Empty>相簿還是空的。在手機上加照片，或在聊天時傳照片給他、請他存進相簿。</Empty>;
  return (
    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      {data.map((p) => (
        <Card key={p.id} className="gap-0 overflow-hidden py-0">
          <img src={resolveFileUrl(p.path)} alt="" className="aspect-[4/3] w-full object-cover" loading="lazy" />
          <div className="space-y-1 p-4">
            {p.hisCaption && <p>「{p.hisCaption}」</p>}
            {p.herCaption && <p className="text-sm text-muted-foreground">你：{p.herCaption}</p>}
            <p className="text-xs text-muted-foreground">{fmt(p.at)}</p>
          </div>
        </Card>
      ))}
    </div>
  );
}

// ───────── 页面 ─────────

export default function HoshioPage() {
  const [tab, setTab] = React.useState<Tab>("overview");
  const [key, setKey] = React.useState(0);
  return (
    <div className="min-h-screen bg-background">
      <div className="mx-auto max-w-5xl space-y-5 px-4 py-6">
        <div className="flex items-center gap-3">
          <Button variant="ghost" size="icon" asChild>
            <Link to="/" aria-label="回到聊天">
              <ArrowLeft className="size-5" />
            </Link>
          </Button>
          <h1 className="flex-1 text-2xl font-semibold">✨ 星汐空間</h1>
          <Button variant="ghost" size="icon" aria-label="重新整理" onClick={() => setKey((k) => k + 1)}>
            <RefreshCw className="size-5" />
          </Button>
        </div>
        <div className="flex flex-wrap gap-2">
          {TABS.map((t) => (
            <Button key={t.value} size="sm" variant={tab === t.value ? "default" : "outline"} className="rounded-full" onClick={() => setTab(t.value)}>
              {t.label}
            </Button>
          ))}
        </div>
        <div key={`${tab}-${key}`}>
          {tab === "overview" && <OverviewTab />}
          {tab === "letters" && <LettersTab />}
          {tab === "diary" && <DiaryTab />}
          {tab === "promises" && <PromisesTab />}
          {tab === "capsules" && <CapsulesTab />}
          {tab === "album" && <AlbumTab />}
        </div>
      </div>
    </div>
  );
}
