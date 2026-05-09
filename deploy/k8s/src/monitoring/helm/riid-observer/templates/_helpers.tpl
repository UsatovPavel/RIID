{{- define "riid-observer.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | quote }}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "riid-observer.monitoringAffinity" -}}
{{- if .Values.monitoringNodeAffinity.required }}
nodeAffinity:
  requiredDuringSchedulingIgnoredDuringExecution:
    nodeSelectorTerms:
      - matchExpressions:
          - key: riid.monitoring
            operator: In
            values: ["true"]
          - key: node-role.kubernetes.io/control-plane
            operator: DoesNotExist
          - key: node-role.kubernetes.io/master
            operator: DoesNotExist
{{- end }}
{{- end }}
