{{/*
Standard naming/labeling helpers (conventional Helm chart shape — matches `helm create` output so
the chart is unsurprising to operators already used to Helm).
*/}}

{{- define "eip.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "eip.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "eip.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "eip.labels" -}}
helm.sh/chart: {{ include "eip.chart" . }}
{{ include "eip.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "eip.selectorLabels" -}}
app.kubernetes.io/name: {{ include "eip.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/*
Backend (eip-app) specific names — distinguishes it from the optional frontend workload.
*/}}
{{- define "eip.backend.fullname" -}}
{{- printf "%s-backend" (include "eip.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "eip.backend.selectorLabels" -}}
{{ include "eip.selectorLabels" . }}
app.kubernetes.io/component: backend
{{- end -}}

{{/*
Name of the Secret carrying EIP_APP_DB_PASSWORD / EIP_MIGRATOR_DB_PASSWORD / EIP_SECRETS_MASTER_KEY
— either operator-provided (secrets.existingSecret) or this chart's own secret.yaml.
*/}}
{{- define "eip.secretName" -}}
{{- default (printf "%s-secrets" (include "eip.fullname" .)) .Values.secrets.existingSecret -}}
{{- end -}}

{{/*
Frontend specific names.
*/}}
{{- define "eip.frontend.fullname" -}}
{{- printf "%s-frontend" (include "eip.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "eip.frontend.selectorLabels" -}}
{{ include "eip.selectorLabels" . }}
app.kubernetes.io/component: frontend
{{- end -}}
