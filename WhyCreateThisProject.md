# Why I Created This Project: A 40-Year Developer's Reflections on Building with AI

> **Languages:** [English](WhyCreateThisProject.md) | [繁體中文](WhyCreateThisProject_TW.md)

Repurposing a spare smartphone into a security camera is hardly a new concept. Plenty of existing apps already do it, but the video quality on their free tiers is notoriously dismal.

This marks my first project built entirely with the assistance of AI (Antigravity CLI + Gemini Flash 3.8). From inception to completion, the entire workflow was driven solely through natural language conversations with the AI. Frankly, even today as the codebase reaches completion, I haven't scrutinized a single line of source code.

The project is completely open-source on GitHub: https://github.com/samlu/CamMonitor

It includes the complete source code, user guides, precompiled APKs, and the raw conversation transcripts documenting my interactions with the AI from the very start.

---

Looking back at the process: I started by tossing in a rough draft of requirements riddled with typos and awkward phrasing (`user_requirement.txt`), asking the AI to organize it into a rigorous system specification (`Specification.md`). After a few iterative alignment rounds in under thirty minutes—during which I, the human, was by far the biggest bottleneck—we finalized the full system specification.

Next, I simply directed the AI to start building based on the specification. From an empty directory to a functioning core codebase, it took less than an hour.

Naturally, it wasn’t an overnight push-button miracle. Over the following few days, I spent roughly 20 hours sporadically testing, refining features, and polishing edge cases before reaching its current rock-solid stability.

The most astonishing part of the journey was the testing phase: once I connected two Android phones to my laptop, I handed full control over to the AI. It autonomously tested the P2P connection, detected visual HUD layout defects, rewrote the code, rebuilt, and reinstalled the APKs onto the physical devices. It carried out the entire debugging loop autonomously before reporting back, leaving me only to verify the user experience on real hardware. While real-world development still required back-and-forth iteration, the sheer velocity of this autonomous development and self-debugging fundamentally shattered my understanding of software engineering.

---

All the results are now completely open-source. Feel free to clone it to experience the thrill of having AI code for you, or let AI take over to implement new features like on-device object detection or continuous recording. If you simply want to use the app, precompiled release APKs are ready to install. In keeping with my longstanding tradition, this is Android-only—so please don't ask, "What about iPhone?"

I have no plans to publish this app to Google Play. In a sense, this project serves as a farewell prelude to my forty-plus years as a software developer.

In an era where even complete novices can build software, technical implementation is no longer an insurmountable wall.  
The greatest bottleneck is no longer "how to build it," but whether you can articulate with crystal clarity: *what problem are you actually trying to solve?*  
Never lose your ability to uncover problems—and above all, hold on to your unique imagination.
