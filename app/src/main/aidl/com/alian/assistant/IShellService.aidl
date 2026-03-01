package com.alian.assistant;

interface IShellService {
    void destroy() = 16777114;
    String exec(String command) = 1;
}
