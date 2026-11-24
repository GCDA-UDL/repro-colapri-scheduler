# ColaPri reproduction image: Maven 3.9 + Eclipse Temurin JDK 21, with CloudSim Plus 8.5.7 and all
# other Maven dependencies pre-fetched so that experiments start fast and run offline.
#
#   docker build -t colapri-sim .
#   docker run --rm -v "$PWD/results:/workspace/results" colapri-sim RigorousEval      # EXP-A
#   docker run --rm -v "$PWD/results:/workspace/results" -v "/path/to/traces:/traces:ro" colapri-sim RealTraceRigorous   # EXP-E
#
# Base image pinned by digest (multi-arch index of maven:3.9-eclipse-temurin-21, resolved 2026-10-21).
FROM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320

WORKDIR /workspace

# Warm the dependency cache first (layer is reused while pom.xml is unchanged); also resolve the
# exec-maven-plugin used at run time
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline && mvn -q -B exec:help > /dev/null

# Sources and helper scripts; compile once at build time
COPY src ./src
COPY scripts ./scripts
RUN mvn -q -B compile

# Experiment logs are written to /workspace/results (mount it to keep them on the host)
RUN mkdir -p /workspace/results
VOLUME ["/workspace/results"]

# The single argument is the experiment main class (RigorousEval, DatacenterEval, ScalabilityEval,
# SensitivityEval, RealTraceRigorous, ActivationEval, AgingSweep, StochasticEval). Default: EXP-A.
ENTRYPOINT ["bash", "-lc", "mvn -q -B exec:java -Dexec.mainClass=colapri.$0 -Dexec.cleanupDaemonThreads=false"]
CMD ["RigorousEval"]
