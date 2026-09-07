const ALL_WORKSPACE_TOPICS = [
  'workspace:repos',
  'workspace:slots',
  'workspace:protocols',
  'workspace:lifecycle',
  'workspace:worklog',
];

type Unsubscribe = () => void;

interface Subscription {
  topics: Set<string>;
  callback: (topic: string) => void;
}

let eventSource: EventSource | null = null;
const subscriptions = new Set<Subscription>();

function ensureConnection(): void {
  if (eventSource) return;
  const topicParams = ALL_WORKSPACE_TOPICS
      .map(t => `topics=${encodeURIComponent(t)}`)
      .join('&');
  eventSource = new EventSource(`/api/push?${topicParams}`);
  eventSource.addEventListener('message', handleMessage);
}

function handleMessage(e: Event): void {
  try {
    const msg = JSON.parse((e as MessageEvent).data);
    if (msg.topic) {
      for (const sub of subscriptions) {
        if (sub.topics.has(msg.topic)) {
          sub.callback(msg.topic);
        }
      }
    }
  } catch { /* ignore non-JSON */ }
}

function closeIfEmpty(): void {
  if (subscriptions.size === 0 && eventSource) {
    eventSource.close();
    eventSource = null;
  }
}

export function subscribeWorkspace(
  topics: string[],
  onTopicMessage: (topic: string) => void,
): Unsubscribe {
  const sub: Subscription = {
    topics: new Set(topics),
    callback: onTopicMessage,
  };
  subscriptions.add(sub);
  ensureConnection();
  return () => {
    subscriptions.delete(sub);
    closeIfEmpty();
  };
}

export function _resetForTest(): void {
  if (eventSource) { eventSource.close(); eventSource = null; }
  subscriptions.clear();
}
