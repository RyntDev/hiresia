# Third-Party Licenses & Notices

Hiresia (`com.hiresia.audioplayer`) incorporates the following open-source native libraries, Android dependencies, and services. Each component remains subject to its respective original license.

---

## 1. Native C / C++ Libraries (`app/src/main/cpp/third_party/`)

### Apple Lossless Audio Codec (ALAC) Decoder
- **Path:** `app/src/main/cpp/third_party/alac/`
- **Copyright:** `Copyright (c) 2011 Apple Inc. All rights reserved.`
- **License:** Apache License, Version 2.0

```text
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

---

### WavPack 5.7.0
- **Path:** `app/src/main/cpp/third_party/wavpack/`
- **Copyright:** `Copyright (c) 1998 - 2024 David Bryant. All Rights Reserved.`
- **License:** BSD 3-Clause License

```text
Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.
3. Neither the name of the copyright holder nor the names of its contributors
   may be used to endorse or promote products derived from this software without
   specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

---

### dr_libs (`dr_flac.h`, `dr_mp3.h`, `dr_wav.h`)
- **Path:** `app/src/main/cpp/third_party/dr_flac.h`, `dr_mp3.h`, `dr_wav.h`
- **Author:** David Reid (`https://github.com/mackron/dr_libs`)
- **License:** Dual-licensed — Public Domain (Unlicense) or MIT No Attribution (MIT-0)

```text
ALTERNATIVE 1 - Public Domain (www.unlicense.org)
This is free and unencumbered software released into the public domain.
For more information, please refer to <http://unlicense.org/>

ALTERNATIVE 2 - MIT No Attribution
Copyright 2023 David Reid

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
of the Software, and to permit persons to whom the Software is furnished to do
so.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

### stb_vorbis (`stb_vorbis.c` v1.25)
- **Path:** `app/src/main/cpp/third_party/stb_vorbis.c`
- **Author:** Sean Barrett (`https://github.com/nothings/stb`)
- **License:** Dual-licensed — MIT License or Public Domain (Unlicense)

```text
ALTERNATIVE A - MIT License
Copyright (c) 2017 Sean Barrett

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
of the Software, and to permit persons to whom the Software is furnished to do
so, subject to the following conditions:
The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

ALTERNATIVE B - Public Domain (www.unlicense.org)
This is free and unencumbered software released into the public domain.
```

---

## 2. Gradle / Android Dependencies & Online Services

| Component | Purpose | License |
| :--- | :--- | :--- |
| **AndroidX (`core-ktx`, `appcompat`, `constraintlayout`, `lifecycle`, `media`, `documentfile`)** | Core UI, lifecycle & storage support | Apache License 2.0 |
| **Material Components for Android** | Material Design UI widgets | Apache License 2.0 |
| **Jetpack Media3 (`media3-exoplayer`, `media3-session`)** | Media session integration | Apache License 2.0 |
| **Kotlin Coroutines (`kotlinx-coroutines-android`)** | Asynchronous coroutines | Apache License 2.0 |
| **Glide** | Asynchronous album art image loading | BSD / MIT / Apache License 2.0 |
| **LRCLIB (`https://lrclib.net/`)** | Optional online synced lyrics lookup | Open API / Community Lyrics Service |

