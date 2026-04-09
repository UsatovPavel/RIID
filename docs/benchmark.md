# scenario - a:
Local:
   [DaemonScenarioAPodmanColdCachePullsTest] riid_pull_ms_list=[14044, 13257, 12431, 13031, 11777]
    [DaemonScenarioAPodmanColdCachePullsTest] riid_sum_pull_ms=64540 riid_phase_wall_ms=67289 riid_median_pull_ms=13031
    [DaemonScenarioAPodmanColdCachePullsTest] podman_pull_ms_list=[11573, 11553, 10704, 10211, 10595]
    [DaemonScenarioAPodmanColdCachePullsTest] podman_sum_pull_ms=54636 podman_phase_wall_ms=55972 podman_median_pull_ms=10704

## Yandex-cloud
       [DaemonScenarioAPodmanColdCachePullsTest] riid i=1/5 pull_ms=23940
    [DaemonScenarioAPodmanColdCachePullsTest] riid i=2/5 pull_ms=11836
    [DaemonScenarioAPodmanColdCachePullsTest] riid i=3/5 pull_ms=11299
    [DaemonScenarioAPodmanColdCachePullsTest] riid i=4/5 pull_ms=10450
    [DaemonScenarioAPodmanColdCachePullsTest] riid i=5/5 pull_ms=11167
    [DaemonScenarioAPodmanColdCachePullsTest] podman i=1/5 pull_ms=5753 ref=docker.io/library/irssi:latest
    [DaemonScenarioAPodmanColdCachePullsTest] podman i=2/5 pull_ms=8074 ref=docker.io/library/irssi:latest
    [DaemonScenarioAPodmanColdCachePullsTest] podman i=3/5 pull_ms=8560 ref=docker.io/library/irssi:latest
    [DaemonScenarioAPodmanColdCachePullsTest] podman i=4/5 pull_ms=8365 ref=docker.io/library/irssi:latest
    [DaemonScenarioAPodmanColdCachePullsTest] podman i=5/5 pull_ms=8365 ref=docker.io/library/irssi:latest
    [DaemonScenarioAPodmanColdCachePullsTest] riid_pull_ms_list=[23940, 11836, 11299, 10450, 11167]
    [DaemonScenarioAPodmanColdCachePullsTest] riid_sum_pull_ms=68692 riid_phase_wall_ms=71348 riid_median_pull_ms=11299
    [DaemonScenarioAPodmanColdCachePullsTest] podman_pull_ms_list=[5753, 8074, 8560, 8365, 8365]
    [DaemonScenarioAPodmanColdCachePullsTest] podman_sum_pull_ms=39117 podman_phase_wall_ms=40967 podman_median_pull_ms=8365


## scenario - b1

Daemon30ImagesSequentialPullTest > daemonThenPodman() STANDARD_OUT
    [Daemon30ImagesSequentialPullTest] riid i=1/30 repo=library/hello-seattle pull_ms=2058
    [Daemon30ImagesSequentialPullTest] riid i=2/30 repo=library/hola-mundo pull_ms=1998
    [Daemon30ImagesSequentialPullTest] riid i=3/30 repo=library/cirros pull_ms=4147
    [Daemon30ImagesSequentialPullTest] riid i=4/30 repo=library/jobber pull_ms=3393
    [Daemon30ImagesSequentialPullTest] riid i=5/30 repo=library/photon pull_ms=3917
    [Daemon30ImagesSequentialPullTest] riid i=6/30 repo=library/api-firewall pull_ms=7782
    [Daemon30ImagesSequentialPullTest] riid i=7/30 repo=library/eggdrop pull_ms=9831
    [Daemon30ImagesSequentialPullTest] riid i=8/30 repo=library/hitch pull_ms=8406
    [Daemon30ImagesSequentialPullTest] riid i=9/30 repo=library/spiped pull_ms=14902
    [Daemon30ImagesSequentialPullTest] riid i=10/30 repo=library/express-gateway pull_ms=21661
    [Daemon30ImagesSequentialPullTest] riid i=11/30 repo=library/thrift pull_ms=11412
    [Daemon30ImagesSequentialPullTest] riid i=12/30 repo=library/alt pull_ms=29514
    [Daemon30ImagesSequentialPullTest] riid i=13/30 repo=library/hylang pull_ms=22040
    [Daemon30ImagesSequentialPullTest] riid i=14/30 repo=library/irssi pull_ms=11358
    [Daemon30ImagesSequentialPullTest] riid i=15/30 repo=library/unit pull_ms=12943
    [Daemon30ImagesSequentialPullTest] riid i=16/30 repo=library/euleros pull_ms=35258
    [Daemon30ImagesSequentialPullTest] riid i=17/30 repo=library/clefos pull_ms=16272
    [Daemon30ImagesSequentialPullTest] riid i=18/30 repo=library/krakend pull_ms=24615
    [Daemon30ImagesSequentialPullTest] riid i=19/30 repo=library/neurodebian pull_ms=32325
    [Daemon30ImagesSequentialPullTest] riid i=20/30 repo=library/almalinux pull_ms=28679
    [Daemon30ImagesSequentialPullTest] riid i=21/30 repo=library/clearlinux pull_ms=50161
    [Daemon30ImagesSequentialPullTest] riid i=22/30 repo=library/sl pull_ms=43816
    [Daemon30ImagesSequentialPullTest] riid i=23/30 repo=library/fluentd pull_ms=41950
    [Daemon30ImagesSequentialPullTest] riid i=24/30 repo=library/celery pull_ms=51797
    [Daemon30ImagesSequentialPullTest] riid i=25/30 repo=library/rapidoid pull_ms=48101
    [Daemon30ImagesSequentialPullTest] riid i=26/30 repo=library/satosa pull_ms=53284
    [Daemon30ImagesSequentialPullTest] riid i=27/30 repo=library/swipl pull_ms=25080
    [Daemon30ImagesSequentialPullTest] riid i=28/30 repo=library/mageia pull_ms=46685
    [Daemon30ImagesSequentialPullTest] riid i=29/30 repo=library/emqx pull_ms=52891
    [Daemon30ImagesSequentialPullTest] riid i=30/30 repo=library/liquibase pull_ms=34172
    [Daemon30ImagesSequentialPullTest] riid_pull_ms_list=[2058, 1998, 4147, 3393, 3917, 7782, 9831, 8406, 14902, 21661, 11412, 29514, 22040, 11358, 12943, 35258, 16272, 24615, 32325, 28679, 50161, 43816, 41950, 51797, 48101, 53284, 25080, 46685, 52891, 34172]
    [Daemon30ImagesSequentialPullTest] riid_sum_pull_ms=750448 riid_phase_wall_ms=750485
    [Daemon30ImagesSequentialPullTest] podman i=1/30 repo=library/hello-seattle pull_ms=3049 ref=docker.io/library/hello-seattle:latest
    [Daemon30ImagesSequentialPullTest] podman i=2/30 repo=library/hola-mundo pull_ms=3044 ref=docker.io/library/hola-mundo:latest
    [Daemon30ImagesSequentialPullTest] podman i=3/30 repo=library/cirros pull_ms=3749 ref=docker.io/library/cirros:latest
    [Daemon30ImagesSequentialPullTest] podman i=4/30 repo=library/jobber pull_ms=3850 ref=docker.io/library/jobber:latest
    [Daemon30ImagesSequentialPullTest] podman i=5/30 repo=library/photon pull_ms=3647 ref=docker.io/library/photon:latest
    [Daemon30ImagesSequentialPullTest] podman i=6/30 repo=library/api-firewall pull_ms=4351 ref=docker.io/library/api-firewall:latest
    [Daemon30ImagesSequentialPullTest] podman i=7/30 repo=library/eggdrop pull_ms=4653 ref=docker.io/library/eggdrop:latest
    [Daemon30ImagesSequentialPullTest] podman i=8/30 repo=library/hitch pull_ms=4751 ref=docker.io/library/hitch:latest
    [Daemon30ImagesSequentialPullTest] podman i=9/30 repo=library/spiped pull_ms=7157 ref=docker.io/library/spiped:latest
    [Daemon30ImagesSequentialPullTest] podman i=10/30 repo=library/express-gateway pull_ms=24022 ref=docker.io/library/express-gateway:latest
    [Daemon30ImagesSequentialPullTest] podman i=11/30 repo=library/thrift pull_ms=12383 ref=docker.io/library/thrift:latest
    [Daemon30ImagesSequentialPullTest] podman i=12/30 repo=library/alt pull_ms=7660 ref=docker.io/library/alt:latest
    [Daemon30ImagesSequentialPullTest] podman i=13/30 repo=library/hylang pull_ms=16592 ref=docker.io/library/hylang:latest
    [Daemon30ImagesSequentialPullTest] podman i=14/30 repo=library/irssi pull_ms=19308 ref=docker.io/library/irssi:latest
    [Daemon30ImagesSequentialPullTest] podman i=15/30 repo=library/unit pull_ms=16903 ref=docker.io/library/unit:latest
    [Daemon30ImagesSequentialPullTest] podman i=16/30 repo=library/
    euleros pull_ms=9669 ref=docker.io/library/euleros:latest

## b2 
Stress-scenario 30 pulls
{"timestamp":"2026-04-09T18:15:24.287229158Z","message":"Loaded registry-1.docker.io/library/hello-seattle:latest@sha256:7a012702999ac3589aeb46d28ab2b9b6b82324d8504b55e5cf4fdc9a9f52558d into runtime podman via OCI layout stream (~3311 B files under layout)","logger_name":"riid.app.service.ImageLoadingFacade","thread_name":"virtual-100","level":"INFO","level_value":20000,"operation":"archive.build","event":"engine.import","result":"success","duration_ms":34375}
{"timestamp":"2026-04-09T18:19:35.632467478Z","message":"Loaded registry-1.docker.io/library/hola-mundo:latest@sha256:96cbf03396259df63a258b969b2a3f364fdda7e1d6ec0b799d37e9ecc4e04b19 into runtime podman via OCI layout stream (~3316 B files under layout)","logger_name":"riid.app.service.ImageLoadingFacade","thread_name":"virtual-107","level":"INFO","level_value":20000,"operation":"archive.build","event":"engine.import","result":"success","duration_ms":285481}
{"timestamp":"2026-04-09T18:20:32.4278252Z","message":"Loaded registry-1.docker.io/library/photon:latest@sha256:76d28dc460748d0621207b38fa4dc1950c21b33604e2ccabb78b46d7fcf1c784 into runtime podman via OCI layout stream (~16256374 B files under layout)","logger_name":"riid.app.service.ImageLoadingFacade","thread_name":"virtual-88","level":"INFO","level_value":20000,"operation":"archive.build","event":"engine.import","result":"success","duration_ms":342524}
{"timestamp":"2026-04-09T18:20:32.622423012Z","message":"Loaded registry-1.docker.io/library/unit:latest@sha256:f22211ba1c5ea9d0beecccda79a84cb2b7e243287a16298582e6a8757048604d into runtime podman via OCI layout stream (~54948656 B files under layout)","logger_name":"riid.app.service.ImageLoadingFacade","thread_name":"virtual-170","level":"INFO","level_value":20000,"operation":"archive.build","event":"engine.import","result":"success","duration_ms":342732}
{"timestamp":"2026-04-09T18:20:32.915286979Z","message":"Loaded registry-1.docker.io/library/cirros:latest@sha256:bcaa15243a65a89a2647593774048f6bc5a07ecbe31326fc80ba8cf94067eca9 into runtime podman via OCI layout stream (~7417542 B files under layout)","logger_name":"riid.app.service.ImageLoadingFacade","thread_name":"virtual-91","level":"INFO","level_value":20000,"operation":"archive.build","event":"engine.import","result":"success","duration_ms":343039}
{"timestamp":"2026-04-09T18:21:10.531221176Z","message":"Loaded registry-1.docker.io/library/jobber:latest@sha256:898bab42c56c50fcb7f409c0120fde85def5b205eee0855afac12057ceb05bd7 into runtime podman via OCI layout stream (~11770142 B files under layout)","logger_name":"riid.app.service.ImageLoadingFacade","thread_name":"virtual-92","level":"INFO","level_value":20000,"operation":"archive.build","event":"engine.import","result":"success","duration_ms":380659}
{"timestamp":"2026-04-09T18:21:15.321553052Z","message":"Loaded registry-1.docker.io/library/eggdrop:latest@sha256:75604f971a95fc3315b618630920ed7b16ad0bad4e32423780835c1a935a392e into runtime podman via OCI layout stream (~17477047 B files under layout)","logger_name":"riid.app.service.ImageLoadingFacade","thread_name":"virtual-93","level":"INFO","level_value":20000,"operation":"archive.build","event":"engine.import","result":"success","duration_ms":385377}
{"timestamp":"2026-04-09T18:21:15.763161961Z","message":"Loaded registry-1.docker.io/library/thrift:latest@sha256:e0a01e94dc4e65f65f5859ea9cb6dcebe047b71c5e4ee3180b7ebd15c6eb300e into runtime podman via OCI layout stream (~43745898 B files under layout)","logger_name":"riid.app.service.ImageLoadingFacade","thread_name":"virtual-108","level":"INFO","level_value":20000,"operation":"archive.build","event":"engine.import","result":"success","duration_ms":385659}
{"timestamp":"2026-04-09T18:21:26.07299817Z","message":"Loaded registry-1.docker.io/library/express-gateway:latest@sha256:7c4782ac4be03e299c74581fb0ce5ca157a48ce25d9c8a4c07631c39d6107fc1 into runtime podman via OCI layout stream (~40243481 B files under layout)","logger_name":"riid.app.service.ImageLoadingFacade","thread_name":"virtual-89","level":"INFO","level_value":20000,"operation":"archive.build","event":"engine.import","result":"success","duration_ms":396152}
{"timestamp":"2026-04-09T18:22:26.459724861Z","message":"Loaded registry-1.docker.io/library/hitch:latest@sha256:ec3af73ac3870071c5e5d8fe238c1ee4491bc39e5c9003123f4f54024e9ea901 into runtime podman via OCI layout stream (~32264688 B files under layout)","logger_name":"riid.app.service.ImageLoadingFacade","thread_name":"virtual-87","level":"INFO","level_value":20000,"operation":"archive.build","event":"engine.import","result":"success","duration_ms":456253}
{"timestamp":"2026-04-09T18:22:30.12780406Z","message":"Loaded registry-1.docker.io/library/api-firewall:latest@sha256:2f45adad956aca81999813b5dbfb4fcbf6e50ed6e1c7e94aa50d5cb04032d6fe into runtime podman via OCI layout stream (~18499171 B files under layout)","logger_name":"riid.app.service.ImageLoadingFacade","thread_name":"virtual-105","level":"INFO","level_value":20000,"operation":"archive.build","event":"engine.import","result":"success","duration_ms":460222}
{"timestamp":"2026-04-09T18:22:51.633826771Z","message":"Loaded registry-1.docker.io/library/satosa:latest@sha256:83aef0a6c74e27fbb7c2fd815f7d3a51eb2d8bb2869ca818e08d86cc3292649e into runtime podman via OCI layout stream (~91859099 B files under layout)","logger_name":"riid.app.service.ImageLoadingFacade","thread_name":"virtual-361","level":"INFO","level_value":20000,"operation":"archive.build","event":"engine.import","result":"success","duration_ms":481548}
{"timestamp":"2026-04-09T18:22:51.98198378Z","message":"Loaded registry-1.docker.io/library/spiped:latest@sha256:a3e2ba12f248fe7dfe821e5c4544f956fb8f6982156435656feb15535e65d879 into runtime podman via OCI layout stream (~36830699 B files under layout)","logger_name":"riid.app.service.ImageLoadingFacade","thread_name":"virtual-97","level":"INFO","level_value":20000,"operation":"archive.build","event":"engine.import","result":"success","duration_ms":482029}
{"timestamp":"2026-04-09T18:23:37.200238442Z","message":"Loaded registry-1.docker.io/library/fluentd:latest@sha256:2f4043c322f55eec6c6708e4efd629c144448e0a196244c1902a0f1a6c43e721 into runtime podman via OCI layout stream (~79252564 B files under layout)","logger_name":"riid.app.service.ImageLoadingFacade","thread_name":"virtual-174","level":"INFO","level_value":20000,"operation":"archive.build","event":"engine.import","result":"success","duration_ms":527312}
{"timestamp":"2026-04-09T18:23:54.022627739Z","message":"Loaded registry-1.docker.io/library/irssi:latest@sha256:d6648063cefbd933c29ccd69e16863b697f8a9d757707c07451f9dc1d1e1738e into runtime podman via OCI layout stream (~53873979 B files under layout)","logger_name":"riid.app.service.ImageLoadingFacade","thread_name":"virtual-172","level":"INFO","level_value":20000,"operation":"archive.build","event":"engine.import","result":"success","duration_ms":544127}
{"timestamp":"2026-04-09T18:24:17.297582459Z","message":"Loaded registry-1.docker.io/library/hylang:latest@sha256:42ff8a7e029308f2ac717d0a3ad2dc75faa3987d02f808a007ef74efcef526e0 into runtime podman via OCI layout stream (~51859572 B files under layout)","logger_name":"riid.app.service.ImageLoadingFacade","thread_name":"virtual-178","level":"INFO","level_value":20000,"operation":"archive.build","event":"engine.import","result":"success","duration_ms":567397}
{"timestamp":"2026-04-09T18:25:12.727181033Z","message":"Loaded registry-1.docker.io/library/alt:latest@sha256:1d217259914e1adad17b026ccdd3e10d110239fdb28ce1c9464fe3370a0e2fb2 into runtime podman via OCI layout stream (~46190372 B files under layout)","logger_name":"riid.app.service.ImageLoadingFacade","thread_name":"virtual-173","level":"INFO","level_value":20000,"operation":"archive.build","event":"engine.import","result":"success","duration_ms":622813}
{"timestamp":"2026-04-09T18:25:12.873281648Z","message":"Loaded registry-1.docker.io/library/rapidoid:latest@sha256:00fb9871cd9eaa80b7fd9f5e759555ed3459db7b251d2e5a9ee6802e1a0de2b3 into runtime podman via OCI layout stream (~89971488 B files under layout)","logger_name":"riid.app.service.ImageLoadingFacade","thread_name":"virtual-185","level":"INFO","level_value":20000,"operation":"archive.build","event":"engine.import","result":"success","duration_ms":622680}
{"timestamp":"2026-04-09T18:25:17.111930831Z","message":"Loaded registry-1.docker.io/library/neurodebian:latest@sha256:6c104489228738831d5ca33b6baf71ce5cfc18b9e9f7bc0d14ae6a445dfe0c60 into runtime podman via OCI layout stream (~59687994 B files under layout)","logger_name":"riid.app.service.ImageLoadingFacade","thread_name":"virtual-291","level":"INFO","level_value":20000,"operation":"archive.build","event":"engine.import","result":"success","duration_ms":626971}
{"timestamp":"2026-04-09T18:26:06.199620092Z","message":"Loaded registry-1.docker.io/library/swipl:latest@sha256:5d195462b8d0c12da00c21c7e230d7b5591912e75355e68ac69d98c0977af925 into runtime podman via OCI layout stream (~99115950 B files under layout)","logger_name":"riid.app.service.ImageLoadingFacade","thread_name":"virtual-177","level":"INFO","level_value":20000,"operation":"archive.build","event":"engine.import","result":"success","duration_ms":675998}
{"timestamp":"2026-04-09T18:26:10.113317937Z","message":"Loaded registry-1.docker.io/library/celery:latest@sha256:5c236059192a0389a2be21fc42d8db59411d953b7af5457faf501d4eec32dc31 into runtime podman via OCI layout stream (~83637319 B files under layout)","logger_name":"riid.app.service.ImageLoadingFacade","thread_name":"virtual-190","level":"INFO","level_value":20000,"operation":"archive.build","event":"engine.import","result":"success","duration_ms":680326}
{"timestamp":"2026-04-09T18:26:10.188707716Z","message":"Loaded registry-1.docker.io/library/almalinux:latest@sha256:278743ed668444507c164e58d7a6fd5caafb3f3a06cad9dee6ae43323ba4633f into runtime podman via OCI layout stream (~68459983 B files under layout)","logger_name":"riid.app.service.ImageLoadingFacade","thread_name":"virtual-186","level":"INFO","level_value":20000,"operation":"archive.build","event":"engine.import","result":"success","duration_ms":680292}
{"timestamp":"2026-04-09T18:27:06.628507974Z","message":"Loaded registry-1.docker.io/library/euleros:latest@sha256:5574fa79739b9b6f1972add7fcd8245f673c354beee646c71c7f32ee0f7827d3 into runtime podman via OCI layout stream (~57321607 B files under layout)","logger_name":"riid.app.service.ImageLoadingFacade","thread_name":"virtual-188","level":"INFO","level_value":20000,"operation":"archive.build","event":"engine.import","result":"success","duration_ms":736732}
{"timestamp":"2026-04-09T18:27:51.129371171Z","message":"Loaded registry-1.docker.io/library/sl:latest@sha256:221d5f6f69b653baf7058f0d55ad4a31ac700da3fd66fe24872117e300391d52 into runtime podman via OCI layout stream (~71512494 B files under layout)","logger_name":"riid.app.service.ImageLoadingFacade","thread_name":"virtual-175","level":"INFO","level_value":20000,"operation":"archive.build","event":"engine.import","result":"success","duration_ms":781225}
{"timestamp":"2026-04-09T18:28:05.957642669Z","message":"Loaded registry-1.docker.io/library/clearlinux:latest@sha256:e9aef15e29788a0beec6b91c51e648719fbd926e21ef7087c3992548b72cc082 into runtime podman via OCI layout stream (~70595661 B files under layout)","logger_name":"riid.app.service.ImageLoadingFacade","thread_name":"virtual-182","level":"INFO","level_value":20000,"operation":"archive.build","event":"engine.import","result":"success","duration_ms":796015}
{"timestamp":"2026-04-09T18:28:25.56809746Z","message":"Loaded registry-1.docker.io/library/krakend:latest@sha256:9d16d9d50e5d206132401967048d83b833a1744285961f737b25d6a5c87ae51c into runtime podman via OCI layout stream (~59486791 B files under layout)","logger_name":"riid.app.service.ImageLoadingFacade","thread_name":"virtual-293","level":"INFO","level_value":20000,"operation":"archive.build","event":"engine.import","result":"success","duration_ms":815661}
{"timestamp":"2026-04-09T18:28:38.167824933Z","message":"Loaded registry-1.docker.io/library/emqx:latest@sha256:bcf835a6854ff358b666ef02392bf6bf07ab2c96a4114ec07dc0ab1a8c7feb55 into runtime podman via OCI layout stream (~108406222 B files under layout)","logger_name":"riid.app.service.ImageLoadingFacade","thread_name":"virtual-376","level":"INFO","level_value":20000,"operation":"archive.build","event":"engine.import","result":"success","duration_ms":828016}
{"timestamp":"2026-04-09T18:28:41.978183734Z","message":"Loaded registry-1.docker.io/library/mageia:latest@sha256:86bbbbf1f664df41ec9b2db9288d971834be029a5a2f1fb6d667329ce10d89b4 into runtime podman via OCI layout stream (~102330271 B files under layout)","logger_name":"riid.app.service.ImageLoadingFacade","thread_name":"virtual-229","level":"INFO","level_value":20000,"operation":"archive.build","event":"engine.import","result":"success","duration_ms":831824}
{"timestamp":"2026-04-09T18:28:53.01601518Z","message":"Loaded registry-1.docker.io/library/liquibase:latest@sha256:e5ea908f9bec1c44423e19610c1f5ad41f789ba74aa46867e855808f3eda1c9b into runtime podman via OCI layout stream (~111323913 B files under layout)","logger_name":"riid.app.service.ImageLoadingFacade","thread_name":"virtual-396","level":"INFO","level_value":20000,"operation":"archive.build","event":"engine.import","result":"success","duration_ms":843144}
