package com.github.xingjianluo.releaseorchestratorjava.state;

import com.github.xingjianluo.releaseorchestratorjava.model.ReleaseEvent;

import java.util.List;

/** 仅追加事件日志：跨进程文件锁保证交叉执行与中断恢复后历史不乱序、不丢失。 */
public interface EventJournal {

    ReleaseEvent append(String releaseId, String type, String detail);

    List<ReleaseEvent> readAll();
}
