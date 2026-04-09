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
    [Daemon30ImagesSequentialPullTest] podman i=16/30 repo=library/euleros pull_ms=9669 ref=docker.io/library/euleros:latest
