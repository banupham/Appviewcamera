from __future__ import annotations


def retry_delay_seconds(attempts: int, schedule: tuple[int, ...]) -> int:
    if not schedule:
        return 60
    index = max(0, min(len(schedule) - 1, max(1, attempts) - 1))
    return int(schedule[index])
